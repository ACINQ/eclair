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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.{LocalClose, RemoteClose, RevokedClose}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.channel.states.ChannelStateTestsTags.NoMaxHtlcValueInFlight
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, HexStringSyntax}

/**
 * Created by PM on 23/12/2022.
 */

class NormalSplicesStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val tags = test.tags + ChannelStateTestsTags.DualFunding + ChannelStateTestsTags.Splicing
    val setup = init(tags = tags)
    import setup._
    reachNormal(setup, tags)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    withFixture(test.toNoArgTest(setup))
  }

  private val defaultSpliceOutScriptPubKey = hex"0020aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

  private def initiateSplice(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None): Transaction = {
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt, spliceOut_opt)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    if (spliceIn_opt.isDefined) {
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    if (spliceOut_opt.isDefined) {
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)

    sender.expectMsgType[RES_SPLICE]

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])

    val fundingTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    fundingTx
  }

  test("recv CMD_SPLICE (splice-in)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 2_000_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 1_300_000_000.msat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
  }

  test("recv CMD_SPLICE (splice-out)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val feerate = TestConstants.Alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(TestConstants.Alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val expectedMiningFee = Transactions.weight2fee(feerate, fundingTx1.weight())
    val actualMiningFee = 1_400_000.sat - alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity
    // fee computation is approximate
    assert(actualMiningFee - expectedMiningFee < 100.sat || expectedMiningFee - actualMiningFee < 100.sat)
    // initiator pays the fee
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 700_000_000.msat - actualMiningFee)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
  }

  test("recv CMD_SPLICE (splice-out, would go below reserve)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = None, Some(SpliceOut(790_000 sat, defaultSpliceOutScriptPubKey)))
    alice ! cmd
    sender.expectMsgType[RES_FAILURE[_, _]]
  }

  test("recv CMD_SPLICE (splice-in, feerate too low)") { f =>
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = None)
    alice ! cmd
    // we tweak the feerate
    val spliceInit = alice2bob.expectMsgType[SpliceInit].copy(feerate = FeeratePerKw(100.sat))
    alice2bob.forward(bob, spliceInit)
    bob2alice.expectMsgType[TxAbort]
  }

  test("recv CMD_SPLICE (splice-in + splice-out)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    // NB: since there is a splice-in, swap-out fees will be paid from bitcoind so final capacity is predictable
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 1_900_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 1_200_000_000.msat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
  }

  test("recv WatchFundingConfirmedTriggered on splice tx", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val sender = TestProbe()
    // command for a large payment (larger than local balance pre-slice)
    val cmd = CMD_ADD_HTLC(sender.ref, 1_000_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    // first attempt at payment fails (not enough balance)
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)

    val fundingTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage(100 millis)

    // the splice tx isn't yet confirmed, payment still fails
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)

    // the splice tx is only considered locked by alice, payment still fails
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]

    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // now the payment works!
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  private def setup2Splices(f: FixtureParam): (Transaction, Transaction) = {
    import f._

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectNoMessage(100 millis)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    bob2blockchain.expectNoMessage(100 millis)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1, 0))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1, 0))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    (fundingTx1, fundingTx2)
  }

  test("splice local/remote locking", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splice 1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 2 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("splice local/remote locking (reverse order)", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splice 2 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // we don't send a splice_locked for the older tx
    alice2bob.expectNoMessage(100 millis)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // we don't send a splice_locked for the older tx
    bob2alice.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("splice local/remote locking (intermingled)", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splice 1 confirms on alice, splice 2 confirms on bob
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 2 confirms on bob, splice 1 confirms on alice
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectNoMessage(100 millis)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    bob2blockchain.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("emit post-splice events", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    val aliceEvents = TestProbe()
    val bobEvents = TestProbe()
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[AvailableBalanceChanged])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelUpdate])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelDown])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[AvailableBalanceChanged])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelUpdate])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelDown])

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splices haven't been locked, so no event is emitted
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    aliceEvents.expectAvailableBalanceChanged(balance = 1_300_000_000.msat, capacity = 2_000_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 700_000_000.msat, capacity = 2_000_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectAvailableBalanceChanged(balance = 1_800_000_000.msat, capacity = 2_500_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 700_000_000.msat, capacity = 2_500_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC with multiple commitments") { f =>
    import f._
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    val sig1 = alice2bob.expectMsgType[CommitSig]
    assert(sig1.batchSize == 2)
    alice2bob.forward(bob)
    val sig2 = alice2bob.expectMsgType[CommitSig]
    assert(sig2.batchSize == 2)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
  }

  test("recv CMD_ADD_HTLC while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv UpdateAddHtlc while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    // we're holding the splice_init to create a race

    val (preimage, cmdAdd: CMD_ADD_HTLC) = makeCmdAdd(5_000_000 msat, bob.underlyingActor.remoteNodeId, bob.underlyingActor.nodeParams.currentBlockHeight)
    bob ! cmdAdd
    val add = bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    // now we forward the splice_init
    alice2bob.forward(bob)
    // this cancels the splice
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    // but the htlc goes through normally
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
  }

  test("recv UpdateAddHtlc while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    bob2alice.forward(alice, fakeHtlc)
    alice2bob.expectMsgType[Error]
    assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-main-delayed")
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("cancels splice on disconnection") { f =>
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)

    alice ! INPUT_DISCONNECTED
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("don't resend splice_locked when zero-conf channel confirms", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    // splice tx gets published, alice sends splice_locked
    alice ! WatchPublishedTriggered(fundingTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2blockchain.expectWatchFundingConfirmed(fundingTx.txid)
    // splice tx confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectWatchFundingSpent(fundingTx.txid)
  }

  test("re-send splice_locked on reconnection") { f =>
    import f._

    def disconnect(): Unit = {
      alice ! INPUT_DISCONNECTED
      bob ! INPUT_DISCONNECTED
      awaitCond(alice.stateName == OFFLINE)
      awaitCond(bob.stateName == OFFLINE)
    }

    def reconnect() = {
      val aliceInit = Init(alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
      val bobInit = Init(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
      alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
      bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
      alice2bob.expectMsgType[ChannelReestablish]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[ChannelReestablish]
      bob2alice.forward(alice)

      alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
      bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    }

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1a = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val watchConfirmed1b = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed2a = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val watchConfirmed2b = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }

    disconnect()
    reconnect()

    // channel_ready are not re-sent because the channel has already been used (for building splices)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 1 confirms on alice's side
    watchConfirmed1a.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)

    // splice 2 confirms on alice's side
    watchConfirmed2a.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 1 confirms on bob's side
    watchConfirmed1b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 2 confirms on bob's side
    watchConfirmed2b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    // NB: we disconnect *before* transmitting the splice_confirmed to alice

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    // this time alice received the splice_confirmed for funding tx 2
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

  }

  /** Check type of published transactions */
  def assertPublished(probe: TestProbe, desc: String): Transaction = {
    val p = probe.expectMsgType[PublishTx]
    assert(desc == p.desc)
    p match {
      case p: PublishFinalTx => p.tx
      case p: PublishReplaceableTx => p.txInfo.tx
    }
  }

  test("force-close with multiple splices (simple)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed2 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val commitTx2 = assertPublished(alice2blockchain, "commit-tx")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    val watchConfirmedCommit2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    val watchConfirmedClaimMainDelayed2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice ! WatchFundingSpentTriggered(commitTx2)
    alice2blockchain.expectNoMessage(100 millis)

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    alice2blockchain.expectNoMessage(100 millis)

    // splice 2 confirms
    watchConfirmed2.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    alice2blockchain.expectNoMessage(100 millis)

    // commit tx confirms
    watchConfirmedCommit2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, commitTx2)

    // claim-main-delayed tx confirms
    watchConfirmedClaimMainDelayed2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMainDelayed2)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[LocalClose]))
  }

  test("force-close with multiple splices (previous active remote)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-main-delayed")
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice ! WatchFundingSpentTriggered(aliceCommitTx2)
    alice2blockchain.expectNoMessage(100 millis)

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    alice2blockchain.expectNoMessage(100 millis)

    // oops! remote commit for splice 1 is published
    val bobCommitTx1 = bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.active.find(_.fundingTxIndex == 1).get.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    val watchAlternativeConfirmed = alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed]
    alice2blockchain.expectNoMessage(100 millis)

    // remote commit tx confirms
    watchAlternativeConfirmed.replyTo ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    // we're back to the normal handling of remote commit
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val watchConfirmedRemoteCommit = alice2blockchain.expectMsgType[WatchTxConfirmed]
    assert(watchConfirmedRemoteCommit.txId == bobCommitTx1.txid)
    // this one fires immediately, tx is already confirmed
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    val watchConfirmedClaimMain = alice2blockchain.expectMsgType[WatchTxConfirmed]

    // claim-main tx confirms
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }

  test("force-close with multiple splices (previous active revoked)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices, both active

    // bob makes a payment
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // alice puts a watch-spent
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // bob publishes a revoked commitment for fundingTx1!
    val bobRevokedCommitTx = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    // alice watches bob's revoked commit tx, and force-closes with latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobRevokedCommitTx.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx2.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainDelayed2.txid)
    alice2blockchain.expectNoMessage(100 millis)

    // bob's revoked tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    // alice reacts by punishing bob
    val aliceClaimMain1 = assertPublished(alice2blockchain, "remote-main")
    val aliceMainPenalty1 = assertPublished(alice2blockchain, "main-penalty")
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobRevokedCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceClaimMain1.txid)
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid)
    alice2blockchain.expectNoMessage(100 millis)

    // both tx confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain1)
    alice ! WatchOutputSpentTriggered(aliceMainPenalty1)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceMainPenalty1.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty1)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("force-close with multiple splices (inactive remote)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]

    // splice 1 gets published
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // bob makes a payment
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]
    // splice 2 gets published
    alice ! WatchPublishedTriggered(fundingTx2)
    bob ! WatchPublishedTriggered(fundingTx2)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 is now inactive
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices, one active and one inactive, and the inactive initial funding
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // alice puts a watch-spent and prunes the initial funding
    alice2blockchain.expectMsgType[WatchFundingSpent]
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 1)
    // bob publishes his latest commitment for fundingTx1
    val bobCommitTx1 = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    // alice watches bob's revoked commit tx, and force-closes with latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobCommitTx1.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx2.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    alice2blockchain.expectNoMessage(100 millis)

    // bob's remote tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    // we're back to the normal handling of remote commit
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val watchConfirmedRemoteCommit = alice2blockchain.expectMsgType[WatchTxConfirmed]
    assert(watchConfirmedRemoteCommit.txId == bobCommitTx1.txid)
    // this one fires immediately, tx is already confirmed
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    val watchConfirmedClaimMain = alice2blockchain.expectMsgType[WatchTxConfirmed]

    // claim-main tx confirms
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }

  test("force-close with multiple splices (inactive revoked)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]
    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head
    // splice 1 gets published
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]
    // splice 2 gets published
    alice ! WatchPublishedTriggered(fundingTx2)
    bob ! WatchPublishedTriggered(fundingTx2)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 is now inactive
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices, one active and one inactive, and the inactive initial funding
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)

    // bob makes a payment
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // alice puts a watch-spent and prunes the initial funding
    alice2blockchain.expectMsgType[WatchFundingSpent]
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 1)
    // bob publishes a revoked commitment for fundingTx1!
    val bobRevokedCommitTx = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    // alice watches bob's revoked commit tx, and force-closes with latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobRevokedCommitTx.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx2.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    alice2blockchain.expectNoMessage(100 millis)

    // bob's revoked tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    // alice reacts by punishing bob
    val aliceClaimMain1 = assertPublished(alice2blockchain, "remote-main-delayed")
    val aliceMainPenalty1 = assertPublished(alice2blockchain, "main-penalty")
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobRevokedCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceClaimMain1.txid)
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid)
    alice2blockchain.expectNoMessage(100 millis)

    // both tx confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain1)
    alice ! WatchOutputSpentTriggered(aliceMainPenalty1)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceMainPenalty1.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty1)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("put back watches after restart") { f =>
    import f._

    val fundingTx0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val (fundingTx1, fundingTx2) = setup2Splices(f)

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    val aliceData = alice.stateData.asInstanceOf[PersistentChannelData]
    val bobData = bob.stateData.asInstanceOf[PersistentChannelData]

    alice.stop()
    bob.stop()

    alice2blockchain.expectNoMessage(100 millis)

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)

    alice2blockchain.expectMsgType[SetChannelId]
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("put back watches after restart (inactive)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx0)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx0)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx0.txid)

    // create splice 1
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 has been locked, fundingTx0 is inactive

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    val aliceData = alice.stateData.asInstanceOf[PersistentChannelData]
    val bobData = bob.stateData.asInstanceOf[PersistentChannelData]

    alice.stop()
    bob.stop()

    alice2blockchain.expectNoMessage(100 millis)

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)

    alice2blockchain.expectMsgType[SetChannelId]
    alice2blockchain.expectWatchPublished(fundingTx2.txid)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    alice2blockchain.expectNoMessage(100 millis)
  }

}
