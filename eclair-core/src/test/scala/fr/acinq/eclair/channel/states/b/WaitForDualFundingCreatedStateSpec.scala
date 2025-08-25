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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script}
import fr.acinq.eclair.blockchain.SingleKeyOnChainWallet
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, ChannelReestablish, CommitSig, Error, Init, LiquidityAds, OpenDualFundedChannel, TxAbort, TxAckRbf, TxAddInput, TxAddOutput, TxComplete, TxCompleteTlv, TxInitRbf, Warning}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, UInt64, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class WaitForDualFundingCreatedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOpenReplyTo: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, wallet: SingleKeyOnChainWallet, aliceListener: TestProbe, bobListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new SingleKeyOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._
    val channelParams = computeChannelParams(setup, test.tags)
    val aliceListener = TestProbe()
    val bobListener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelAborted])
      bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ChannelAborted])
      alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, dualFunded = true)
      bob ! channelParams.initChannelBob(Some(LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, None)), dualFunded = true)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId] // final channel id
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId] // final channel id
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOpenReplyTo, alice2bob, bob2alice, alice2blockchain, bob2blockchain, wallet, aliceListener, bobListener)))
    }
  }

  test("complete interactive-tx protocol", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    // The initiator sends the first interactive-tx message.
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.expectNoMessage(100 millis)
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

  test("recv invalid interactive-tx message", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val inputA = alice2bob.expectMsgType[TxAddInput]

    // Invalid serial_id.
    alice2bob.forward(bob, inputA.copy(serialId = UInt64(1)))
    bob2alice.expectMsgType[TxAbort]
    awaitCond(wallet.rolledback.length == 1)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)

    // Below dust.
    bob2alice.forward(alice, TxAddOutput(channelId(bob), UInt64(1), 150 sat, Script.write(Script.pay2wpkh(randomKey().publicKey))))
    alice2bob.expectMsgType[TxAbort]
    awaitCond(wallet.rolledback.length == 2)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv tx_complete without nonces (taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddOutput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    val txComplete = bob2alice.expectMsgType[TxComplete]
    assert(txComplete.commitNonces_opt.isDefined)
    bob2alice.forward(alice, txComplete.copy(tlvStream = txComplete.tlvStream.copy(records = txComplete.tlvStream.records.filterNot(_.isInstanceOf[TxCompleteTlv.CommitNonces]))))
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv TxAbort", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxAbort(channelId(alice), hex"deadbeef"))
    val bobTxAbort = bob2alice.expectMsgType[TxAbort]
    awaitCond(wallet.rolledback.size == 1)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)

    bob2alice.forward(alice, bobTxAbort)
    awaitCond(wallet.rolledback.size == 2)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.RemoteError]
  }

  test("recv TxInitRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxInitRbf(channelId(alice), 0, FeeratePerKw(15_000 sat)))
    bob2alice.expectMsgType[Warning]
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)

    bob2alice.forward(alice, TxInitRbf(channelId(bob), 0, FeeratePerKw(15_000 sat)))
    alice2bob.expectMsgType[Warning]
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
    aliceOpenReplyTo.expectNoMessage(100 millis)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv TxAckRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxAckRbf(channelId(alice)))
    bob2alice.expectMsgType[Warning]
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)

    bob2alice.forward(alice, TxAckRbf(channelId(bob)))
    alice2bob.expectMsgType[Warning]
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
    aliceOpenReplyTo.expectNoMessage(100 millis)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    alice ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.RemoteError]

    bob ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 2)
    bobListener.expectMsgType[ChannelAborted]
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
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.Cancelled)

    bob ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(wallet.rolledback.size == 2)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.Disconnected)

    bob ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 2)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED (alice sent commit_sig)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
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
    alice2bob.expectMsgType[TxComplete] // bob doesn't receive alice's tx_complete
    alice2bob.expectMsgType[CommitSig] // bob doesn't receive alice's commit_sig
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)

    val channelId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].channelId
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == CLOSED)

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    assert(alice2bob.expectMsgType[ChannelReestablish].nextFundingTxId_opt.contains(fundingTxId))
    alice ! Error(channelId, "unknown channel")
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! TickChannelOpenTimeout
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.TimedOut)
  }

}