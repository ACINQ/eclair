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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT}
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.wire.protocol.{AcceptChannel, Error, FundingCreated, ChannelReady, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, TimestampSecond, randomKey}
import org.scalatest.{Outcome, Tag}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {

    val setup = init()

    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val pushMsat = if (test.tags.contains(ChannelStateTestsTags.NoPushMsat)) 0.msat else TestConstants.pushMsat
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)

    within(30 seconds) {
      val listener = TestProbe()
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
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
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2blockchain.expectMsgType[WatchFundingSpent]
      bob2blockchain.expectMsgType[WatchFundingSpent]
      alice2blockchain.expectMsgType[WatchFundingConfirmed]
      bob2blockchain.expectMsgType[WatchFundingConfirmed]
      listener.expectMsgType[TransactionPublished] // alice has published the funding transaction
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, listener)))
    }
  }

  test("recv ChannelReady") { f =>
    import f._
    // we create a new listener that registers after alice has published the funding tx
    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])
    // make bob send a ChannelReady msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val txPublished = listener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == fundingTx)
    assert(txPublished.miningFee == 0.sat) // bob is fundee
    assert(listener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    val msg = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(msg))
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
  }

  test("recv WatchFundingConfirmedTriggered") { f =>
    import f._
    // we create a new listener that registers after alice has published the funding tx
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(listener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    awaitCond(alice.stateName == WAIT_FOR_CHANNEL_READY)
    alice2blockchain.expectMsgType[WatchFundingLost]
    val channelReady = alice2bob.expectMsgType[ChannelReady]
    // we always send an alias
    assert(channelReady.alias_opt.isDefined)
  }

  test("recv WatchFundingConfirmedTriggered (bad funding pubkey script)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputScript = fundingTx.txOut.head.copy(publicKeyScript = Script.write(multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputScript))
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, badFundingTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchFundingConfirmedTriggered (bad funding amount)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    val badOutputAmount = fundingTx.txOut.head.copy(amount = 1234567.sat)
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputAmount))
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, badFundingTx)
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
    alice ! CurrentBlockHeight(initialState.waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1)
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CurrentBlockCount (funding timeout not reached)") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    bob ! CurrentBlockHeight(initialState.waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE - 1)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv CurrentBlockCount (funding timeout reached)") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    bob ! CurrentBlockHeight(initialState.waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSED)
  }

  test("migrate waitingSince to waitingSinceBlocks") { f =>
    import f._
    // Before version 0.5.1, eclair used an absolute timestamp instead of a block height for funding timeouts.
    val beforeMigration = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].copy(waitingSince = BlockHeight(TimestampSecond.now().toLong))
    bob.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    bob ! INPUT_RESTORED(beforeMigration)
    awaitCond(bob.stateName == OFFLINE)
    // We reset the waiting period to the current block height when starting up after updating eclair.
    val currentBlockHeight = bob.underlyingActor.nodeParams.currentBlockHeight
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].waitingSince == currentBlockHeight)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.NoPushMsat)) { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "funding double-spent")
    bob2blockchain.expectNoMessage(100 millis) // we don't publish our commit tx when we have nothing at stake
    awaitCond(bob.stateName == CLOSED)
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
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

}
