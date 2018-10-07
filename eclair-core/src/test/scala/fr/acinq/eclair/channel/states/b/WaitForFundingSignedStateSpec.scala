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
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, Error, FundingCreated, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForFundingSignedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
      withFixture(test.toNoArgTest(FixtureParam(alice, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv FundingSigned with valid signature") { f =>
    import f._
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv FundingSigned with invalid signature") { f =>
    import f._
    // sending an invalid sig
    alice ! FundingSigned("00" * 32, BinaryData("00" * 64))
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
