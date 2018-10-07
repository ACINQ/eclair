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

package fr.acinq.eclair.channel.states.c

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForFundingLockedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, router: TestProbe)

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
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_LOCKED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, router)))
    }
  }

  test("recv FundingLocked") { f =>
    import f._
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NORMAL)
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error("00" * 32, "oops".getBytes)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_LOCKED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CMD_FORCECLOSE
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }
}
