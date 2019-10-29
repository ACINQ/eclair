/*
 * Copyright 2018 ACINQ SAS
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
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome

import scala.concurrent.duration._


class WaitForFundingSignedTurboStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.fromValidHex("00" * 32), TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.PrivateThenAnnounceTurbo, ChannelVersion.USE_PUBKEY_KEYPATH)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.fromValidHex("00" * 32), Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)))
    }
  }

  test("recv FundingSigned with valid signature") { f =>
    import f._
    val sender = TestProbe()
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSeenInMempool]
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.expectMsgType[WatchSeenInMempool]
    alice2bob.forward(bob, WatchEventSeenInMempool(BITCOIN_FUNDING_DEPTHOK, fundingTx))
    bob2alice.forward(alice, WatchEventSeenInMempool(BITCOIN_FUNDING_DEPTHOK, fundingTx))
    bob2alice.forward(alice, bob2alice.expectMsgType[FundingLocked])
    alice2bob.forward(bob, alice2bob.expectMsgType[FundingLocked])
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    bob2alice.forward(alice, bob2alice.expectMsgType[AssignScid])
    val aliceScidReply = alice2bob.expectMsgType[AssignScidReply]
    alice2bob.forward(bob, aliceScidReply)
    val aliceChanUpdate = alice2bob.expectMsgType[ChannelUpdate]
    alice2bob.forward(bob, aliceChanUpdate)
    val bobChanUpdate = bob2alice.expectMsgType[ChannelUpdate]
    bob2alice.forward(alice, bobChanUpdate)
    assert(aliceChanUpdate.shortChannelId === aliceScidReply.shortChannelId)
    assert(bobChanUpdate.shortChannelId === aliceScidReply.shortChannelId)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == aliceScidReply.shortChannelId)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == aliceScidReply.shortChannelId)
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 500002, 22, null))
    sender.send(bob, alice2bob.expectMsgType[AnnouncementSignatures])
    bob2alice.expectNoMsg(100 millis) // Bob still has not received BITCOIN_FUNDING_DEEPLYBURIED so can't react to announcement sigs, but re-schedules receiving them
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 500002, 22, null))
    sender.send(alice, bob2alice.expectMsgType[AnnouncementSignatures])
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelFlags === ChannelFlags.Announce)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.channelFlags === ChannelFlags.Announce)
  }

  test("recv FundingSigned with invalid signature") { f =>
    import f._
    // sending an invalid sig
    alice ! FundingSigned(ByteVector32.fromValidHex("00" * 32), ByteVector64.fromValidHex("00" * 64))
    awaitCond(alice.stateName == CLOSED)
    alice2bob.expectMsgType[Error]
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    alice ! CMD_CLOSE(None)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    alice ! CMD_FORCECLOSE
    awaitCond(alice.stateName == CLOSED)
  }
}