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
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, TestConstants, TestKitBaseClass}
import fr.acinq.eclair.KotlinUtils._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingLockedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val relayFees: RelayFees = RelayFees(999 msat, 1234)

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, router: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(bobParams.nodeId, relayFees)
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob2blockchain.expectMsgType[WatchFundingSpent]
      bob2blockchain.expectMsgType[WatchFundingConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
      alice ! WatchFundingConfirmedTriggered(400000, 42, fundingTx)
      bob ! WatchFundingConfirmedTriggered(400000, 42, fundingTx)
      alice2blockchain.expectMsgType[WatchFundingLost]
      bob2blockchain.expectMsgType[WatchFundingLost]
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
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.feeBaseMsat === relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths === relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(new Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx.txid === tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx.txid === tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_FUNDING_LOCKED)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val sender = TestProbe()
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx.txid === tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }
}
