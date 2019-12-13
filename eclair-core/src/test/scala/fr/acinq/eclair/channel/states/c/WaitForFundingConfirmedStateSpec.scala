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

package fr.acinq.eclair.channel.states.c

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Script, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.wire.{AcceptChannel, Error, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{LongToBtcAmount, TestConstants, TestkitBaseClass, randomKey}
import org.scalatest.Outcome

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingConfirmedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty, ChannelVersion.STANDARD)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit)
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
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv FundingLocked") { f =>
    import f._
    // make bob send a FundingLocked msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42, fundingTx)
    val msg = bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(msg))
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
  }

  test("recv BITCOIN_FUNDING_DEPTHOK") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42, fundingTx)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
    alice2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
  }

  test("recv BITCOIN_FUNDING_DEPTHOK (bad funding pubkey script)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputScript = fundingTx.txOut(0).copy(publicKeyScript = Script.write(multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputScript))
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42, badFundingTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_DEPTHOK (bad funding amount)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputAmount = fundingTx.txOut(0).copy(amount = 1234567.sat)
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputAmount))
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42, badFundingTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED") { f =>
    import f._
    alice ! BITCOIN_FUNDING_PUBLISH_FAILED
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT") { f =>
    import f._
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsg(PublishAsap(tx))
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_CONFIRMED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CMD_FORCECLOSE
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

}
