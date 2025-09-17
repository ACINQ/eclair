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
import fr.acinq.eclair.channel.publish.TxPublisher.PublishFinalTx
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelReady, Error, FundingCreated, FundingSigned, OpenChannel, TlvStream}
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._
    val channelParams = computeChannelParams(setup, test.tags)
    val pushAmount = if (test.tags.contains(ChannelStateTestsTags.NoPushAmount)) 0.msat else TestConstants.initiatorPushAmount
    within(30 seconds) {
      val listener = TestProbe()
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelClosed])
      bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelClosed])
      alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, pushAmount_opt = Some(pushAmount))
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! channelParams.initChannelBob()
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
      alice2blockchain.expectMsgType[WatchFundingConfirmed]
      bob2blockchain.expectMsgType[WatchFundingConfirmed]
      listener.expectMsgType[TransactionPublished] // alice has published the funding transaction
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, listener)))
    }
  }

  test("recv ChannelReady (funder, with remote alias)") { f =>
    import f._
    // make bob send a ChannelReady msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    assert(bobChannelReady.alias_opt.isDefined)
    // test starts here
    bob2alice.forward(alice)
    // alice stops waiting for confirmations since bob is accepting the channel
    assert(alice2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
    alice ! WatchPublishedTriggered(fundingTx)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    assert(aliceChannelReady.alias_opt.nonEmpty)
    awaitAssert(assert(alice.stateName == NORMAL))
  }

  test("recv ChannelReady (funder, no remote alias)") { f =>
    import f._
    // make bob send a ChannelReady msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReadyNoAlias = bob2alice.expectMsgType[ChannelReady].copy(tlvStream = TlvStream.empty)
    // test starts here
    bob2alice.forward(alice, channelReadyNoAlias)
    // alice keeps bob's channel_ready for later processing
    eventually {
      assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(channelReadyNoAlias))
    }
    alice2blockchain.expectNoMessage()
  }

  test("recv ChannelReady (fundee)") { f =>
    import f._
    // make alice send a ChannelReady msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReady = alice2bob.expectMsgType[ChannelReady]
    // test starts here
    alice2bob.forward(bob)
    // alice keeps bob's channel_ready for later processing
    eventually {
      assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred.contains(channelReady))
    }
    // bob is fundee, he doesn't trust alice and won't create a zero-conf watch
    bob2blockchain.expectNoMessage()
  }

  test("recv WatchFundingConfirmedTriggered (funder)") { f =>
    import f._
    // we create a new listener that registers after alice has published the funding tx
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(listener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    awaitCond(alice.stateName == WAIT_FOR_CHANNEL_READY)
    val channelReady = alice2bob.expectMsgType[ChannelReady]
    // we always send an alias
    assert(channelReady.alias_opt.isDefined)
  }

  test("recv WatchFundingConfirmedTriggered (fundee)") { f =>
    import f._
    // we create a new listener that registers after alice has published the funding tx
    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])
    // make bob send a ChannelReady msg
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(listener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    awaitCond(bob.stateName == WAIT_FOR_CHANNEL_READY)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    // we always send an alias
    assert(channelReady.alias_opt.isDefined)
  }

  test("recv WatchFundingConfirmedTriggered (bad funding pubkey script)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    val badOutputScript = fundingTx.txOut.head.copy(publicKeyScript = Script.write(multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputScript))
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, badFundingTx)
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchFundingConfirmedTriggered (bad funding amount)") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    val badOutputAmount = fundingTx.txOut.head.copy(amount = 1234567.sat)
    val badFundingTx = fundingTx.copy(txOut = Seq(badOutputAmount))
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, badFundingTx)
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED") { f =>
    import f._
    alice ! BITCOIN_FUNDING_PUBLISH_FAILED
    alice2bob.expectMsgType[Error]
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT (funder)") { f =>
    import f._
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT (fundee)") { f =>
    import f._
    bob ! BITCOIN_FUNDING_TIMEOUT
    bob2alice.expectMsgType[Error]
    listener.expectMsgType[ChannelAborted]
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
    listener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv WatchFundingSpentTriggered (remote commit)", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.signCommitTx()
    alice ! WatchFundingSpentTriggered(tx)
    assert(listener.expectMsgType[TransactionPublished].tx == tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)") { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    listener.expectMsgType[ChannelAborted]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    val tx = bob.signCommitTx()
    bob ! Error(ByteVector32.Zeroes, "please help me recover my funds")
    // We have nothing at stake, but we publish our commitment to help our peer recover their funds more quickly.
    awaitCond(bob.stateName == CLOSING)
    listener.expectMsgType[ChannelAborted]
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
    bob ! WatchTxConfirmedTriggered(BlockHeight(42), 1, tx)
    listener.expectMsgType[ChannelClosed]
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
    val tx = alice.signCommitTx()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    listener.expectMsgType[ChannelAborted]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

}
