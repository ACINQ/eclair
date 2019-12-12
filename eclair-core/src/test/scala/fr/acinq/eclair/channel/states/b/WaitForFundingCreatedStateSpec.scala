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

package fr.acinq.eclair.channel.states.b

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{LongToBtcAmount, TestConstants, TestkitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.{Outcome, Tag}
import scodec.bits._

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForFundingCreatedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val (fundingSatoshis, pushMsat) = if (test.tags.contains("funder_below_reserve")) {
      (1000100 sat, (1000000 sat).toMilliSatoshi) // toLocal = 100 satoshis
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }
    val channelVersion = if(test.tags.contains("static_remotekey")) ChannelVersion.STATIC_REMOTEKEY else ChannelVersion.STANDARD
    val (aliceParams, bobParams) = if(test.tags.contains("static_remotekey"))
      (Alice.channelParams.copy(globalFeatures = hex"2000"), Bob.channelParams.copy(globalFeatures = hex"2000"))
    else
      (Alice.channelParams, Bob.channelParams)

    val aliceInit = Init(aliceParams.globalFeatures, aliceParams.localFeatures)
    val bobInit = Init(bobParams.globalFeatures, bobParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelVersion)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, bob2blockchain)))
    }
  }

  test("recv FundingCreated") { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.channelVersion == ChannelVersion.STANDARD)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv FundingCreated (option_static_remotekey)", Tag("static_remotekey")) { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.channelVersion == ChannelVersion.STATIC_REMOTEKEY)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED].channelVersion == ChannelVersion.STATIC_REMOTEKEY)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv FundingCreated (funder can't pay fees)", Tag("funder_below_reserve")) { f =>
    import f._
    val fees = Satoshi(Transactions.commitWeight * TestConstants.feeratePerKw / 1000)
    val reserve = Bob.channelParams.channelReserve
    val missing = 100.sat - fees - reserve
    val fundingCreated = alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(fundingCreated.temporaryChannelId, s"can't pay the fee: missing=${-missing} reserve=$reserve fees=$fees"))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    bob ! CMD_CLOSE(None)
    awaitCond(bob.stateName == CLOSED)
  }

}
