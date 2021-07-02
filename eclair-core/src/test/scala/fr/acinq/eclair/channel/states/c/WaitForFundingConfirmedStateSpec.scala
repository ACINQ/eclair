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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.StateTestsBase
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.wire.protocol.{AcceptChannel, Error, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val channelVersion = ChannelVersion.STANDARD
    val aliceInit = Init(Alice.channelParams.features)
    val bobInit = Init(Bob.channelParams.features)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelVersion)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit, channelVersion)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2blockchain.expectMsgType[WatchFundingSpent]
      alice2blockchain.expectMsgType[WatchFundingConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv FundingLocked") { f =>
    import f._
    // make bob send a FundingLocked msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! WatchFundingConfirmedTriggered(42000, 42, fundingTx)
    val msg = bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(msg))
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
  }

  test("recv WatchFundingConfirmedTriggered") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchFundingConfirmedTriggered(42000, 42, fundingTx)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
    alice2blockchain.expectMsgType[WatchFundingLost]
    alice2bob.expectMsgType[FundingLocked]
  }

  test("recv WatchFundingConfirmedTriggered (bad funding pubkey script)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputScript = fundingTx.txOut.head.copy(publicKeyScript = Script.write(multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputScript))
    alice ! WatchFundingConfirmedTriggered(42000, 42, badFundingTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchFundingConfirmedTriggered (bad funding amount)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputAmount = fundingTx.txOut.head.copy(amount = 1234567.sat)
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputAmount))
    alice ! WatchFundingConfirmedTriggered(42000, 42, badFundingTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED") { f =>
    import f._
    alice ! BITCOIN_FUNDING_PUBLISH_FAILED
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT (funder)") { f =>
    import f._
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT (fundee)") { f =>
    import f._
    bob ! BITCOIN_FUNDING_TIMEOUT
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funder)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    alice ! CurrentBlockCount(initialState.waitingSinceBlock + Channel.FUNDING_TIMEOUT_FUNDEE + 1)
    alice2bob.expectNoMsg(100 millis)
  }

  test("recv CurrentBlockCount (funding timeout not reached)") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    bob ! CurrentBlockCount(initialState.waitingSinceBlock + Channel.FUNDING_TIMEOUT_FUNDEE - 1)
    bob2alice.expectNoMsg(100 millis)
  }

  test("recv CurrentBlockCount (funding timeout reached)") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    bob ! CurrentBlockCount(initialState.waitingSinceBlock + Channel.FUNDING_TIMEOUT_FUNDEE + 1)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSED)
  }

  test("migrate waitingSince to waitingSinceBlocks") { f =>
    import f._
    // Before version 0.5.1, eclair used an absolute timestamp instead of a block height for funding timeouts.
    val beforeMigration = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].copy(waitingSinceBlock = System.currentTimeMillis().milliseconds.toSeconds)
    bob.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    bob ! INPUT_RESTORED(beforeMigration)
    awaitCond(bob.stateName == OFFLINE)
    // We reset the waiting period to the current block height when starting up after updating eclair.
    val currentBlockHeight = bob.underlyingActor.nodeParams.currentBlockHeight
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].waitingSinceBlock === currentBlockHeight)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchFundingSpentTriggered(tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx === tx)
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx === tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_CONFIRMED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val sender = TestProbe()
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx === tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }

}
