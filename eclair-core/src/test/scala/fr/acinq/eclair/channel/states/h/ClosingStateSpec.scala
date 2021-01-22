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

package fr.acinq.eclair.channel.states.h

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, OutPoint, SatoshiLong, ScriptFlags, Transaction, TxIn}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class ClosingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, relayerA: TestProbe, relayerB: TestProbe, channelUpdateListener: TestProbe, bobCommitTxes: List[PublishableTxs])

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._

    // NOTE
    // As opposed to other tests, we won't reach the target state (here CLOSING) at the end of the fixture.
    // The reason for this is that we may reach CLOSING state following several events:
    // - local commit
    // - remote commit
    // - revoked commit
    // and we want to be able to test the different scenarii.
    // Hence the WAIT_FOR_FUNDING_CONFIRMED->CLOSING or NORMAL->CLOSING transition will occur in the individual tests.

    val unconfirmedFundingTx = test.tags.contains("funding_unconfirmed")

    if (unconfirmedFundingTx) {
      within(30 seconds) {
        val aliceInit = Init(Alice.channelParams.features)
        val bobInit = Init(Bob.channelParams.features)
        alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty, ChannelVersion.STANDARD)
        bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit, ChannelVersion.STANDARD)
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
        awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
        awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
        withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayerA, relayerB, channelUpdateListener, Nil)))
      }
    } else {
      within(30 seconds) {
        reachNormal(setup, test.tags)
        val bobCommitTxs: List[PublishableTxs] = (for (amt <- List(100000000 msat, 200000000 msat, 300000000 msat)) yield {
          val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
          crossSign(alice, bob, alice2bob, bob2alice)
          relayerB.expectMsgType[RelayForward]
          val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
          fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
          // alice forwards the fulfill upstream
          relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fulfill]]
          crossSign(bob, alice, bob2alice, alice2bob)
          // bob confirms that it has forwarded the fulfill to alice
          awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(htlc.channelId).isEmpty)
          val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
          bobCommitTx1 :: bobCommitTx2 :: Nil
        }).flatten

        awaitCond(alice.stateName == NORMAL)
        awaitCond(bob.stateName == NORMAL)
        withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayerA, relayerB, channelUpdateListener, bobCommitTxs)))
      }
    }
  }

  test("start fee negotiation from configured block target") { f =>
    import f._

    alice.feeEstimator.setFeerate(FeeratesPerKw(FeeratePerKw(50 sat), FeeratePerKw(100 sat), FeeratePerKw(250 sat), FeeratePerKw(350 sat), FeeratePerKw(450 sat), FeeratePerKw(600 sat), FeeratePerKw(800 sat), FeeratePerKw(900 sat), FeeratePerKw(1000 sat)))

    val sender = TestProbe()
    // alice initiates a closing
    alice ! CMD_CLOSE(sender.ref, None)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    val closing = alice2bob.expectMsgType[ClosingSigned]
    val aliceData = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
    val mutualClosingFeeRate = alice.feeEstimator.getFeeratePerKw(alice.feeTargets.mutualCloseBlockTarget)
    val expectedFirstProposedFee = Closing.firstClosingFee(aliceData.commitments, aliceData.localShutdown.scriptPubKey, aliceData.remoteShutdown.scriptPubKey, mutualClosingFeeRate)(akka.event.NoLogging)
    assert(alice.feeTargets.mutualCloseBlockTarget == 2 && mutualClosingFeeRate == FeeratePerKw(250 sat))
    assert(closing.feeSatoshis == expectedFirstProposedFee)
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed

    // test starts here
    alice ! BITCOIN_FUNDING_PUBLISH_FAILED
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_TIMEOUT", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed

    // test starts here
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv GetTxResponse (funder, tx found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    alice2blockchain.expectMsgType[WatchConfirmed] // commitment
    alice2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), System.currentTimeMillis.milliseconds.toSeconds)
    alice2bob.expectNoMsg(200 millis)
    alice2blockchain.expectNoMsg(200 millis)
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (funder, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    alice2blockchain.expectMsgType[WatchConfirmed] // commitment
    alice2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, None, System.currentTimeMillis.milliseconds.toSeconds)
    alice2bob.expectNoMsg(200 millis)
    alice2blockchain.expectMsg(PublishAsap(fundingTx)) // we republish the funding tx
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), System.currentTimeMillis.milliseconds.toSeconds)
    bob2alice.expectNoMsg(200 millis)
    bob2blockchain.expectNoMsg(200 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, System.currentTimeMillis.milliseconds.toSeconds)
    bob2alice.expectNoMsg(200 millis)
    bob2blockchain.expectNoMsg(200 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found, timeout)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    bob.setState(stateData = bob.stateData.asInstanceOf[DATA_CLOSING].copy(waitingSince = System.currentTimeMillis.milliseconds.toSeconds - 15.days.toSeconds))
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, System.currentTimeMillis.milliseconds.toSeconds)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMsg(200 millis)
    assert(bob.stateName == CLOSED)
  }

  test("recv GetTxResponse (fundee, tx not found, timeout, blockchain lags)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchConfirmed] // claim-main-delayed

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, System.currentTimeMillis.milliseconds.toSeconds - 3.hours.toSeconds)
    bob2alice.expectNoMsg(200 millis)
    bob2blockchain.expectNoMsg(200 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, ByteVector32(ByteVector.fill(32)(1)), cltvExpiry = CltvExpiry(300000), onion = TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_FULFILL_HTLC (unexisting htlc)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val c = CMD_FULFILL_HTLC(42, randomBytes32, replyTo_opt = Some(sender.ref))
    alice ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(alice), 42)))

    // NB: nominal case is tested in IntegrationSpec
  }

  def testMutualCloseBeforeConverge(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._
    val sender = TestProbe()
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)
    // alice initiates a closing
    alice ! CMD_CLOSE(sender.ref, None)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    // agreeing on a closing fee
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(100 sat)))
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    bob2alice.forward(alice)
    // they don't converge yet, but alice has a publishable commit tx now
    assert(aliceCloseFee != bobCloseFee)
    val Some(mutualCloseTx) = alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    // let's make alice publish this closing tx
    alice ! Error(ByteVector32.Zeroes, "")
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(mutualCloseTx))
    assert(mutualCloseTx === alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last)

    // actual test starts here
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, mutualCloseTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0, mutualCloseTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_SPENT (mutual close before converging)") { f =>
    testMutualCloseBeforeConverge(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_FUNDING_SPENT (mutual close before converging, anchor outputs)", Tag("anchor_outputs")) { f =>
    testMutualCloseBeforeConverge(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  test("recv BITCOIN_TX_CONFIRMED (mutual close)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last

    // actual test starts here
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0, mutualCloseTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_FUNDING_SPENT (local commit)") { f =>
    import f._
    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // actual test starts here
    // we are notified afterwards from our watcher about the tx that we just published
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, aliceCommitTx)
    assert(alice.stateData == initialState) // this was a no-op
  }

  test("recv BITCOIN_OUTPUT_SPENT") { f =>
    import f._
    // alice sends an htlc to bob
    val (ra1, htlca1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    relayerB.expectMsgType[RelayForward]
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]

    // scenario 1: bob claims the htlc output from the commit tx using its preimage
    val claimHtlcSuccessFromCommitTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomBytes32, 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessClaimHtlcSuccessFromCommitTx(Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33))) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, claimHtlcSuccessFromCommitTx)
    val fulfill1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill1.htlc === htlca1)
    assert(fulfill1.result.paymentPreimage === ra1)

    // scenario 2: bob claims the htlc output from his own commit tx using its preimage (let's assume both parties had published their commitment tx)
    val claimHtlcSuccessTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomBytes32, 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessHtlcSuccess(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33), Transactions.DefaultCommitmentFormat)) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, claimHtlcSuccessTx)
    val fulfill2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill2.htlc === htlca1)
    assert(fulfill2.result.paymentPreimage === ra1)

    assert(alice.stateData == initialState) // this was a no-op
  }

  def testLocalCommitTxConfirmed(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[LocalCommitConfirmed])
    system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])

    // alice sends an htlc to bob
    val (_, htlca1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends an htlc below dust to bob
    val amountBelowDust = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.dustLimit - 100.msat
    val (_, htlca2) = addHtlc(amountBelowDust, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val closingState = localClose(alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainDelayedOutputTx.isDefined)
    assert(closingState.htlcSuccessTxs.isEmpty)
    assert(closingState.htlcTimeoutTxs.length === 1)
    assert(closingState.claimHtlcDelayedTxs.length === 1)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.commitTx), 42, 0, closingState.commitTx)
    assert(listener.expectMsgType[LocalCommitConfirmed].refundAtBlock == 42 + TestConstants.Bob.channelParams.toSelfDelay.toInt)
    assert(listener.expectMsgType[PaymentSettlingOnChain].paymentHash == htlca1.paymentHash)
    // htlcs below dust will never reach the chain, once the commit tx is confirmed we can consider them failed
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc === htlca2)
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainDelayedOutputTx.get), 200, 0, closingState.claimMainDelayedOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.htlcTimeoutTxs.head), 201, 0, closingState.htlcTimeoutTxs.head)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.irrevocablySpent.values.toSet === Set(
      closingState.commitTx.txid,
      closingState.claimMainDelayedOutputTx.get.txid,
      closingState.htlcTimeoutTxs.head.txid
    ))
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc === htlca1)
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcDelayedTxs.head), 202, 0, closingState.claimHtlcDelayedTxs.head)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit)") { f =>
    testLocalCommitTxConfirmed(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit, anchor outputs)", Tag("anchor_outputs")) { f =>
    testLocalCommitTxConfirmed(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit with multiple htlcs for the same payment)") { f =>
    import f._

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(30000000 msat, alice, bob, alice2bob, bob2alice)
    // and more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(25000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(30000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    val amountBelowDust = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.dustLimit - 100.msat
    val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val dust = addHtlc(dustCmd, alice, bob, alice2bob, bob2alice)
    val (_, cmd4) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight + 1, ra1)
    val htlca4 = addHtlc(cmd4, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val closingState = localClose(alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainDelayedOutputTx.isDefined)
    assert(closingState.htlcSuccessTxs.isEmpty)
    assert(closingState.htlcTimeoutTxs.length === 4)
    assert(closingState.claimHtlcDelayedTxs.length === 4)

    // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.htlcTimeoutTxs.head), 42, 0, closingState.htlcTimeoutTxs.head)
    val forwardedFail1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.commitTx), 42, 1, closingState.commitTx)
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc === dust)
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainDelayedOutputTx.get), 200, 0, closingState.claimMainDelayedOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.htlcTimeoutTxs(1)), 202, 0, closingState.htlcTimeoutTxs(1))
    val forwardedFail2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.htlcTimeoutTxs(2)), 202, 1, closingState.htlcTimeoutTxs(2))
    val forwardedFail3 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.htlcTimeoutTxs(3)), 203, 0, closingState.htlcTimeoutTxs(3))
    val forwardedFail4 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3, forwardedFail4) === Set(htlca1, htlca2, htlca3, htlca4))
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcDelayedTxs.head), 203, 0, closingState.claimHtlcDelayedTxs.head)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcDelayedTxs(1)), 203, 1, closingState.claimHtlcDelayedTxs(1))
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcDelayedTxs(2)), 203, 2, closingState.claimHtlcDelayedTxs(2))
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcDelayedTxs(3)), 203, 3, closingState.claimHtlcDelayedTxs(3))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit with htlcs only signed by local)") { f =>
    import f._
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    // alice sends an htlc
    val (_, htlc) = addHtlc(4200000 msat, alice, bob, alice2bob, bob2alice)
    // and signs it (but bob doesn't sign it)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    // note that bob doesn't receive the new sig!
    // then we make alice unilaterally close the channel
    val closingState = localClose(alice, alice2blockchain)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcSuccessTxs.isEmpty && closingState.htlcTimeoutTxs.isEmpty && closingState.claimHtlcDelayedTxs.isEmpty)
    // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(aliceCommitTx), 0, 0, aliceCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    relayerA.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMsg(2 seconds)
    relayerA.expectNoMsg(100 millis)
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit with fulfill only signed by local)") { f =>
    import f._
    // bob sends an htlc
    val (r, htlc) = addHtlc(110000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    relayerA.expectMsgType[RelayForward]
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(aliceCommitTx.txOut.size === 3) // 2 main outputs + 1 htlc

    // alice fulfills the HTLC but bob doesn't receive the signature
    alice ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    alice2bob.expectMsgType[UpdateFulfillHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    // note that bob doesn't receive the new sig!
    // then we make alice unilaterally close the channel
    val closingState = localClose(alice, alice2blockchain)
    assert(closingState.commitTx === aliceCommitTx)
    assert(closingState.htlcTimeoutTxs.isEmpty)
    assert(closingState.htlcSuccessTxs.size === 1)
    assert(closingState.claimHtlcDelayedTxs.size === 1)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit with htlcs only signed by local in next remote commit)") { f =>
    import f._
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    // alice sends an htlc
    val (_, htlc) = addHtlc(4200000 msat, alice, bob, alice2bob, bob2alice)
    // and signs it (but bob doesn't sign it)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty && closingState.claimHtlcTimeoutTxs.isEmpty)
    // when the commit tx is signed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    relayerA.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMsg(2 seconds)
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxes.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty && closingState.claimHtlcTimeoutTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.commitments.channelVersion === ChannelVersion.STANDARD)
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxes.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty && closingState.claimHtlcTimeoutTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 0, 0, closingState.claimMainOutputTx.get)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit, option_static_remotekey)", Tag("static_remotekey")) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].commitments.channelVersion === ChannelVersion.STATIC_REMOTEKEY)
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxes.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // alice won't create a claimMainOutputTx because her main output is already spendable by the wallet
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimMainOutputTx.isEmpty)
    assert(alice.stateName == CLOSING)
    // once the remote commit is confirmed the channel is definitively closed
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit, anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.commitments.channelVersion === ChannelVersion.ANCHOR_OUTPUTS)
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxes.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 4) // two main outputs + two anchors
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty && closingState.claimHtlcTimeoutTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 0, 0, closingState.claimMainOutputTx.get)
    awaitCond(alice.stateName == CLOSED)
  }

  def testRemoteCommitTxWithHtlcsConfirmed(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(15000000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(15000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight - 1, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob publishes the latest commit tx.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    if (channelVersion.hasAnchorOutputs) {
      assert(bobCommitTx.txOut.length === 7) // two main outputs + two anchors + 3 HTLCs
    } else {
      assert(bobCommitTx.txOut.length === 5) // two main outputs + 3 HTLCs
    }
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimHtlcTimeoutTxs.length === 3)

    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 45, 0, closingState.claimMainOutputTx.get)
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs.head), 201, 0, closingState.claimHtlcTimeoutTxs.head)
    val forwardedFail1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(1)), 202, 0, closingState.claimHtlcTimeoutTxs(1))
    val forwardedFail2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(2)), 203, 1, closingState.claimHtlcTimeoutTxs(2))
    val forwardedFail3 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) === Set(htlca1, htlca2, htlca3))
    relayerA.expectNoMsg(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit with multiple htlcs for the same payment)") { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit with multiple htlcs for the same payment, anchor outputs)", Tag("anchor_outputs")) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit) followed by CMD_FULFILL_HTLC") { f =>
    import f._
    // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
    val (r1, htlc1) = addHtlc(110000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    relayerA.expectMsgType[RelayForward]

    // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
    val (_, htlc2) = addHtlc(95000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig] // We stop here: Alice sent her CommitSig, but doesn't hear back from Bob.

    // Now Bob publishes the first commit tx (force-close).
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.length === 3) // two main outputs + 1 HTLC
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty && closingState.claimHtlcTimeoutTxs.isEmpty) // we don't have the preimage to claim the htlc-success yet

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)
    alice2blockchain.expectMsg(PublishAsap(closingState.claimMainOutputTx.get))
    val claimHtlcSuccessTx = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.head
    Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectMsg(PublishAsap(claimHtlcSuccessTx))

    // Alice resets watches on all relevant transactions.
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get))
    val watchHtlcSuccess = alice2blockchain.expectMsgType[WatchSpent]
    assert(watchHtlcSuccess.event === BITCOIN_OUTPUT_SPENT)
    assert(watchHtlcSuccess.txId === bobCommitTx.txid)
    assert(watchHtlcSuccess.outputIndex === claimHtlcSuccessTx.txIn.head.outPoint.index)
    alice2blockchain.expectNoMsg(100 millis)

    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    // The second htlc was not included in the commit tx published on-chain, so we can consider it failed
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc === htlc2)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 0, 0, closingState.claimMainOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcSuccessTx), 0, 0, claimHtlcSuccessTx)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.irrevocablySpent.values.toSet === Set(
      bobCommitTx.txid,
      closingState.claimMainOutputTx.get.txid,
      claimHtlcSuccessTx.txid
    ))
    awaitCond(alice.stateName == CLOSED)
    alice2blockchain.expectNoMsg(100 millis)
    relayerA.expectNoMsg(100 millis)
  }

  private def testNextRemoteCommitTxConfirmed(f: FixtureParam, channelVersion: ChannelVersion): (Transaction, RemoteCommitPublished, Set[UpdateAddHtlc]) = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(15000000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // The last one is only signed by Alice: Bob has two spendable commit tx.
    val (_, cmd3) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not forwarded to Alice (malicious Bob)
    bob2alice.expectMsgType[CommitSig] // not forwarded to Alice (malicious Bob)

    // Bob publishes the next commit tx.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    if (channelVersion.hasAnchorOutputs) {
      assert(bobCommitTx.txOut.length === 7) // two main outputs + two anchors + 3 HTLCs
    } else {
      assert(bobCommitTx.txOut.length === 5) // two main outputs + 3 HTLCs
    }
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimHtlcTimeoutTxs.length === 3)
    (bobCommitTx, closingState, Set(htlca1, htlca2, htlca3))
  }

  test("recv BITCOIN_TX_CONFIRMED (next remote commit)") { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelVersion.STANDARD)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 45, 0, closingState.claimMainOutputTx.get)
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs.head), 201, 0, closingState.claimHtlcTimeoutTxs.head)
    val forwardedFail1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(1)), 202, 0, closingState.claimHtlcTimeoutTxs(1))
    val forwardedFail2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(2)), 203, 1, closingState.claimHtlcTimeoutTxs(2))
    val forwardedFail3 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) === htlcs)
    relayerA.expectNoMsg(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (next remote commit, static_remotekey)", Tag("static_remotekey")) { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelVersion.STATIC_REMOTEKEY)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 0, bobCommitTx)
    assert(closingState.claimMainOutputTx.isEmpty) // with static_remotekey we don't claim out main output
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs.head), 201, 0, closingState.claimHtlcTimeoutTxs.head)
    val forwardedFail1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(1)), 202, 0, closingState.claimHtlcTimeoutTxs(1))
    val forwardedFail2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(2)), 203, 1, closingState.claimHtlcTimeoutTxs(2))
    val forwardedFail3 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) === htlcs)
    relayerA.expectNoMsg(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (next remote commit, anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelVersion.ANCHOR_OUTPUTS)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 42, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 45, 0, closingState.claimMainOutputTx.get)
    relayerA.expectNoMsg(100 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs.head), 201, 0, closingState.claimHtlcTimeoutTxs.head)
    val forwardedFail1 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(1)), 202, 0, closingState.claimHtlcTimeoutTxs(1))
    val forwardedFail2 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    relayerA.expectNoMsg(250 millis)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimHtlcTimeoutTxs(2)), 203, 1, closingState.claimHtlcTimeoutTxs(2))
    val forwardedFail3 = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) === htlcs)
    relayerA.expectNoMsg(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (next remote commit) followed by CMD_FULFILL_HTLC") { f =>
    import f._
    // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
    val (r1, htlc1) = addHtlc(110000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    relayerA.expectMsgType[RelayForward]

    // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
    val (_, htlc2) = addHtlc(95000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not forwarded to Alice (malicious Bob)
    bob2alice.expectMsgType[CommitSig] // not forwarded to Alice (malicious Bob)

    // Now Bob publishes the next commit tx (force-close).
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.length === 4) // two main outputs + 2 HTLCs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcSuccessTxs.isEmpty) // we don't have the preimage to claim the htlc-success yet
    assert(closingState.claimHtlcTimeoutTxs.length === 1)
    val claimHtlcTimeoutTx = closingState.claimHtlcTimeoutTxs.head

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)
    alice2blockchain.expectMsg(PublishAsap(closingState.claimMainOutputTx.get))
    val claimHtlcSuccessTx = alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcSuccessTxs.head
    Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectMsg(PublishAsap(claimHtlcSuccessTx))
    alice2blockchain.expectMsg(PublishAsap(claimHtlcTimeoutTx))

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get))
    val watchHtlcs = alice2blockchain.expectMsgType[WatchSpent] :: alice2blockchain.expectMsgType[WatchSpent] :: Nil
    watchHtlcs.foreach(ws => assert(ws.event === BITCOIN_OUTPUT_SPENT))
    watchHtlcs.foreach(ws => assert(ws.txId === bobCommitTx.txid))
    assert(watchHtlcs.map(_.outputIndex).toSet === (claimHtlcSuccessTx :: closingState.claimHtlcTimeoutTxs).map(_.txIn.head.outPoint.index).toSet)
    alice2blockchain.expectNoMsg(100 millis)

    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(closingState.claimMainOutputTx.get), 0, 0, closingState.claimMainOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcSuccessTx), 0, 0, claimHtlcSuccessTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcTimeoutTx), 0, 0, claimHtlcTimeoutTx)
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc === htlc2)
    awaitCond(alice.stateName == CLOSED)
    alice2blockchain.expectNoMsg(100 millis)
    relayerA.expectNoMsg(100 millis)
  }

  private def testFutureRemoteCommitTxConfirmed(f: FixtureParam, channelVersion: ChannelVersion): Transaction = {
    import f._
    val oldStateData = alice.stateData
    assert(oldStateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)
    // This HTLC will be fulfilled.
    val (ra1, htlca1) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
    // These 2 HTLCs should timeout on-chain, but since alice lost data, she won't be able to claim them.
    val (ra2, _) = addHtlc(15000000 msat, alice, bob, alice2bob, bob2alice)
    val (_, cmd) = makeCmdAdd(15000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra2)
    addHtlc(cmd, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlca1.id, ra1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    // we simulate a disconnection
    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)
    // then we reconnect them
    val aliceInit = Init(TestConstants.Alice.nodeParams.features)
    val bobInit = Init(TestConstants.Bob.nodeParams.features)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(alice)).getMessage)
    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)
    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    if (channelVersion.hasAnchorOutputs) {
      assert(bobCommitTx.txOut.length === 6) // two main outputs + two anchors + 2 HTLCs
    } else {
      assert(bobCommitTx.txOut.length === 4) // two main outputs + 2 HTLCs
    }
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)
    bobCommitTx
  }

  test("recv BITCOIN_TX_CONFIRMED (future remote commit)") { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelVersion.STANDARD)
    // alice is able to claim its main output
    val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    Transaction.correctlySpends(claimMainTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobCommitTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === claimMainTx.txid)
    alice2blockchain.expectNoMsg(250 millis) // alice ignores the htlc-timeout

    // actual test starts here
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0, claimMainTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv BITCOIN_TX_CONFIRMED (future remote commit, option_static_remotekey)", Tag("static_remotekey")) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelVersion.STATIC_REMOTEKEY)
    // using option_static_remotekey alice doesn't need to sweep her output
    awaitCond(alice.stateName == CLOSING, 10 seconds)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    // after the commit tx is confirmed the channel is closed, no claim transactions needed
    awaitCond(alice.stateName == CLOSED, 10 seconds)
  }

  test("recv BITCOIN_TX_CONFIRMED (future remote commit, anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelVersion.ANCHOR_OUTPUTS)
    // alice is able to claim its main output
    val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    Transaction.correctlySpends(claimMainTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobCommitTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === claimMainTx.txid)
    alice2blockchain.expectNoMsg(250 millis) // alice ignores the htlc-timeout

    // actual test starts here
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0, bobCommitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0, claimMainTx)
    awaitCond(alice.stateName == CLOSED)
  }

  case class RevokedCloseFixture(bobRevokedTxs: Seq[PublishableTxs], htlcsAlice: Seq[UpdateAddHtlc], htlcsBob: Seq[UpdateAddHtlc])

  private def prepareRevokedClose(f: FixtureParam, channelVersion: ChannelVersion): RevokedCloseFixture = {
    import f._

    // Bob's first commit tx doesn't contain any htlc
    val commitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
    if (channelVersion.hasAnchorOutputs) {
      assert(commitTx1.commitTx.tx.txOut.size === 4) // 2 main outputs + 2 anchors
    } else {
      assert(commitTx1.commitTx.tx.txOut.size === 2) // 2 main outputs
    }

    // Bob's second commit tx contains 1 incoming htlc and 1 outgoing htlc
    val (commitTx2, htlcAlice1, htlcBob1) = {
      val (_, htlcAlice) = addHtlc(35000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (_, htlcBob) = addHtlc(20000000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val commitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
      (commitTx, htlcAlice, htlcBob)
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.size == commitTx2.commitTx.tx.txOut.size)
    if (channelVersion.hasAnchorOutputs) {
      assert(commitTx2.commitTx.tx.txOut.size === 6)
    } else {
      assert(commitTx2.commitTx.tx.txOut.size === 4)
    }

    // Bob's third commit tx contains 2 incoming htlcs and 2 outgoing htlcs
    val (commitTx3, htlcAlice2, htlcBob2) = {
      val (_, htlcAlice) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (_, htlcBob) = addHtlc(18000000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val commitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
      (commitTx, htlcAlice, htlcBob)
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.size == commitTx3.commitTx.tx.txOut.size)
    if (channelVersion.hasAnchorOutputs) {
      assert(commitTx3.commitTx.tx.txOut.size === 8)
    } else {
      assert(commitTx3.commitTx.tx.txOut.size === 6)
    }

    // Bob's fourth commit tx doesn't contain any htlc
    val commitTx4 = {
      Seq(htlcAlice1, htlcAlice2).foreach(htlcAlice => failHtlc(htlcAlice.id, bob, alice, bob2alice, alice2bob))
      Seq(htlcBob1, htlcBob2).foreach(htlcBob => failHtlc(htlcBob.id, alice, bob, alice2bob, bob2alice))
      crossSign(alice, bob, alice2bob, bob2alice)
      bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.size == commitTx4.commitTx.tx.txOut.size)
    if (channelVersion.hasAnchorOutputs) {
      assert(commitTx4.commitTx.tx.txOut.size === 4)
    } else {
      assert(commitTx4.commitTx.tx.txOut.size === 2)
    }

    RevokedCloseFixture(Seq(commitTx1, commitTx2, commitTx3, commitTx4), Seq(htlcAlice1, htlcAlice2), Seq(htlcBob1, htlcBob2))
  }

  private def testFundingSpentRevokedTx(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, channelVersion)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)

    // bob publishes one of his revoked txs
    val bobRevokedTx = revokedCloseFixture.bobRevokedTxs(1).commitTx.tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx === bobRevokedTx)
    if (!channelVersion.paysDirectlyToWallet) {
      assert(rvk.claimMainOutputTx.nonEmpty)
    }
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.size === 2)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)
    val penaltyTxs = rvk.claimMainOutputTx.toList ++ rvk.mainPenaltyTx.toList ++ rvk.htlcPenaltyTxs

    // alice publishes the penalty txs
    if (!channelVersion.paysDirectlyToWallet) {
      alice2blockchain.expectMsg(PublishAsap(rvk.claimMainOutputTx.get))
    }
    alice2blockchain.expectMsg(PublishAsap(rvk.mainPenaltyTx.get))
    assert(Set(alice2blockchain.expectMsgType[PublishAsap].tx, alice2blockchain.expectMsgType[PublishAsap].tx) === rvk.htlcPenaltyTxs.toSet)
    for (penaltyTx <- penaltyTxs) {
      Transaction.correctlySpends(penaltyTx, bobRevokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    // alice spends all outpoints of the revoked tx, except her main output when it goes directly to our wallet
    val spentOutpoints = penaltyTxs.flatMap(_.txIn.map(_.outPoint)).toSet
    assert(spentOutpoints.forall(_.txid === bobRevokedTx.txid))
    if (channelVersion.hasAnchorOutputs) {
      assert(spentOutpoints.size === bobRevokedTx.txOut.size - 2) // we don't claim the anchors
    }
    else if (channelVersion.paysDirectlyToWallet) {
      assert(spentOutpoints.size === bobRevokedTx.txOut.size - 1) // we don't claim our main output, it directly goes to our wallet
    } else {
      assert(spentOutpoints.size === bobRevokedTx.txOut.size)
    }

    // alice watches confirmation for the outputs only her can claim
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobRevokedTx.txid)
    if (!channelVersion.paysDirectlyToWallet) {
      assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === rvk.claimMainOutputTx.get.txid)
    }

    // alice watches outputs that can be spent by both parties
    val watchedOutpoints = Seq(alice2blockchain.expectMsgType[WatchSpent], alice2blockchain.expectMsgType[WatchSpent], alice2blockchain.expectMsgType[WatchSpent]).map(_.outputIndex).toSet
    assert(watchedOutpoints === (rvk.mainPenaltyTx.get :: rvk.htlcPenaltyTxs).map(_.txIn.head.outPoint.index).toSet)
    alice2blockchain.expectNoMsg(1 second)

    // once all txs are confirmed, alice can move to the closed state
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobRevokedTx), 100, 3, bobRevokedTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.mainPenaltyTx.get), 110, 1, rvk.mainPenaltyTx.get)
    if (!channelVersion.paysDirectlyToWallet) {
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.claimMainOutputTx.get), 110, 2, rvk.claimMainOutputTx.get)
    }
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.htlcPenaltyTxs.head), 115, 0, rvk.htlcPenaltyTxs.head)
    assert(alice.stateName === CLOSING)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.htlcPenaltyTxs(1)), 115, 2, rvk.htlcPenaltyTxs(1))
    awaitCond(alice.stateName === CLOSED)
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx)") { f =>
    testFundingSpentRevokedTx(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx, option_static_remotekey)", Tag("static_remotekey")) { f =>
    testFundingSpentRevokedTx(f, ChannelVersion.STATIC_REMOTEKEY)
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx, anchor outputs)", Tag("anchor_outputs")) { f =>
    testFundingSpentRevokedTx(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  test("recv BITCOIN_FUNDING_SPENT (multiple revoked tx)") { f =>
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, ChannelVersion.STANDARD)
    assert(revokedCloseFixture.bobRevokedTxs.map(_.commitTx.tx.txid).toSet.size === revokedCloseFixture.bobRevokedTxs.size) // all commit txs are distinct

    def broadcastBobRevokedTx(revokedTx: Transaction, htlcCount: Int, revokedCount: Int): RevokedCommitPublished = {
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
      awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == revokedCount)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.last.commitTx === revokedTx)

      // alice publishes penalty txs
      val claimMain = alice2blockchain.expectMsgType[PublishAsap].tx
      val mainPenalty = alice2blockchain.expectMsgType[PublishAsap].tx
      val htlcPenaltyTxs = (1 to htlcCount).map(_ => alice2blockchain.expectMsgType[PublishAsap].tx)
      (claimMain +: mainPenalty +: htlcPenaltyTxs).foreach(tx => Transaction.correctlySpends(tx, revokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

      // alice watches confirmation for the outputs only her can claim
      assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === revokedTx.txid)
      assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === claimMain.txid)

      // alice watches outputs that can be spent by both parties
      assert(alice2blockchain.expectMsgType[WatchSpent].outputIndex === mainPenalty.txIn.head.outPoint.index)
      val htlcOutpoints = (1 to htlcCount).map(_ => alice2blockchain.expectMsgType[WatchSpent].outputIndex).toSet
      assert(htlcOutpoints === htlcPenaltyTxs.flatMap(_.txIn.map(_.outPoint.index)).toSet)
      alice2blockchain.expectNoMsg(1 second)

      alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.last
    }

    // bob publishes a first revoked tx (no htlc in that commitment)
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs.head.commitTx.tx, 0, 1)
    // bob publishes a second revoked tx
    val rvk2 = broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(1).commitTx.tx, 2, 2)
    // bob publishes a third revoked tx
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(2).commitTx.tx, 4, 3)

    // bob's second revoked tx confirms: once all penalty txs are confirmed, alice can move to the closed state
    // NB: if multiple txs confirm in the same block, we may receive the events in any order
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk2.mainPenaltyTx.get), 100, 1, rvk2.mainPenaltyTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk2.claimMainOutputTx.get), 100, 2, rvk2.claimMainOutputTx.get)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk2.commitTx), 100, 3, rvk2.commitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk2.htlcPenaltyTxs.head), 115, 0, rvk2.htlcPenaltyTxs.head)
    assert(alice.stateName === CLOSING)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk2.htlcPenaltyTxs(1)), 115, 2, rvk2.htlcPenaltyTxs(1))
    awaitCond(alice.stateName === CLOSED)
  }

  def testOutputSpentRevokedTx(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, channelVersion)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)

    // bob publishes one of his revoked txs
    val bobRevokedTxs = revokedCloseFixture.bobRevokedTxs(2)
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTxs.commitTx.tx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx === bobRevokedTxs.commitTx.tx)
    if (channelVersion.paysDirectlyToWallet) {
      assert(rvk.claimMainOutputTx.isEmpty)
    } else {
      assert(rvk.claimMainOutputTx.nonEmpty)
    }
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.size === 4)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)

    // alice publishes the penalty txs and watches outputs
    val claimTxsCount = if (channelVersion.paysDirectlyToWallet) 5 else 6 // 2 main outputs and 4 htlcs
    (1 to claimTxsCount).foreach(_ => alice2blockchain.expectMsgType[PublishAsap])
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === rvk.commitTx.txid)
    if (!channelVersion.paysDirectlyToWallet) {
      assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === rvk.claimMainOutputTx.get.txid)
    }
    (1 to 5).foreach(_ => alice2blockchain.expectMsgType[WatchSpent]) // main output penalty and 4 htlc penalties
    alice2blockchain.expectNoMsg(1 second)

    // bob manages to claim 2 htlc outputs before alice can penalize him: 1 htlc-success and 1 htlc-timeout.
    val bobHtlcSuccessTx1 = bobRevokedTxs.htlcTxsAndSigs.filter(tx => tx.txinfo.input.txOut.amount == revokedCloseFixture.htlcsAlice.head.amountMsat.truncateToSatoshi).head
    val bobHtlcTimeoutTx = bobRevokedTxs.htlcTxsAndSigs.filter(tx => tx.txinfo.input.txOut.amount == revokedCloseFixture.htlcsBob.last.amountMsat.truncateToSatoshi).head
    val bobOutpoints = Seq(bobHtlcSuccessTx1, bobHtlcTimeoutTx).map(_.txinfo.input.outPoint).toSet
    assert(bobOutpoints.size === 2)

    // alice reacts by publishing penalty txs that spend bob's htlc transactions
    alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx1.txinfo.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 1)
    val claimHtlcSuccessPenalty1 = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.head
    Transaction.correctlySpends(claimHtlcSuccessPenalty1, bobHtlcSuccessTx1.txinfo.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobHtlcSuccessTx1.txinfo.tx.txid)
    assert(alice2blockchain.expectMsgType[PublishAsap].tx === claimHtlcSuccessPenalty1)
    val watchSpent1 = alice2blockchain.expectMsgType[WatchSpent]
    assert(watchSpent1.txId === bobHtlcSuccessTx1.txinfo.tx.txid)
    assert(Set(watchSpent1.outputIndex) === claimHtlcSuccessPenalty1.txIn.map(_.outPoint.index).toSet)
    alice2blockchain.expectNoMsg(1 second)

    alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, bobHtlcTimeoutTx.txinfo.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 2)
    val claimHtlcTimeoutPenalty = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.last
    Transaction.correctlySpends(claimHtlcTimeoutPenalty, bobHtlcTimeoutTx.txinfo.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobHtlcTimeoutTx.txinfo.tx.txid)
    assert(alice2blockchain.expectMsgType[PublishAsap].tx === claimHtlcTimeoutPenalty)
    val watchSpent2 = alice2blockchain.expectMsgType[WatchSpent]
    assert(watchSpent2.txId === bobHtlcTimeoutTx.txinfo.tx.txid)
    assert(Set(watchSpent2.outputIndex) === claimHtlcTimeoutPenalty.txIn.map(_.outPoint.index).toSet)
    alice2blockchain.expectNoMsg(1 second)

    // bob RBFs his htlc-success with a different transaction
    val bobHtlcSuccessTx2 = bobHtlcSuccessTx1.txinfo.tx.copy(txIn = TxIn(OutPoint(randomBytes32, 0), Nil, 0) +: bobHtlcSuccessTx1.txinfo.tx.txIn)
    assert(bobHtlcSuccessTx2.txid !== bobHtlcSuccessTx1.txinfo.tx.txid)
    alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx2)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobHtlcSuccessTx2.txid)
    val claimHtlcSuccessPenalty2 = alice2blockchain.expectMsgType[PublishAsap].tx
    assert(claimHtlcSuccessPenalty1.txid != claimHtlcSuccessPenalty2.txid)
    Transaction.correctlySpends(claimHtlcSuccessPenalty2, bobHtlcSuccessTx2 :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val watchSpent3 = alice2blockchain.expectMsgType[WatchSpent]
    assert(watchSpent3.txId === bobHtlcSuccessTx2.txid)
    assert(Set(watchSpent3.outputIndex) === claimHtlcSuccessPenalty2.txIn.map(_.outPoint.index).toSet)
    alice2blockchain.expectNoMsg(1 second)

    // transactions confirm: alice can move to the closed state
    val remainingHtlcPenaltyTxs = rvk.htlcPenaltyTxs.filterNot(tx => bobOutpoints.contains(tx.txIn.head.outPoint))
    assert(remainingHtlcPenaltyTxs.size === 2)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.commitTx), 100, 3, rvk.commitTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.mainPenaltyTx.get), 110, 0, rvk.mainPenaltyTx.get)
    if (!channelVersion.paysDirectlyToWallet) {
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(rvk.claimMainOutputTx.get), 110, 1, rvk.claimMainOutputTx.get)
    }
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(remainingHtlcPenaltyTxs.head), 110, 2, remainingHtlcPenaltyTxs.head)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(remainingHtlcPenaltyTxs.last), 115, 2, remainingHtlcPenaltyTxs.last)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobHtlcTimeoutTx.txinfo.tx), 115, 0, bobHtlcTimeoutTx.txinfo.tx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx2), 115, 1, bobHtlcSuccessTx2)
    assert(alice.stateName === CLOSING)

    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcTimeoutPenalty), 120, 0, claimHtlcTimeoutPenalty)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcSuccessPenalty2), 121, 0, claimHtlcSuccessPenalty2)
    awaitCond(alice.stateName === CLOSED)
  }

  test("recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published htlc-success tx)") { f =>
    testOutputSpentRevokedTx(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published htlc-success tx, option_static_remotekey)", Tag("static_remotekey")) { f =>
    testOutputSpentRevokedTx(f, ChannelVersion.STATIC_REMOTEKEY)
  }

  test("recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published htlc-success tx, anchor outputs)", Tag("anchor_outputs")) { f =>
    testOutputSpentRevokedTx(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  private def testRevokedTxConfirmed(f: FixtureParam, channelVersion: ChannelVersion): Unit = {
    import f._
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion === channelVersion)
    val initOutputCount = if (channelVersion.hasAnchorOutputs) 4 else 2
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.size === initOutputCount)

    // bob's second commit tx contains 2 incoming htlcs
    val (bobRevokedTx, htlcs1) = {
      val (_, htlc1) = addHtlc(35000000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc2) = addHtlc(20000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      assert(bobCommitTx.txOut.size === initOutputCount + 2)
      (bobCommitTx, Seq(htlc1, htlc2))
    }

    // bob's third commit tx contains 1 of the previous htlcs and 2 new htlcs
    val htlcs2 = {
      val (_, htlc3) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc4) = addHtlc(18000000 msat, alice, bob, alice2bob, bob2alice)
      failHtlc(htlcs1.head.id, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.size === initOutputCount + 3)
      Seq(htlc3, htlc4)
    }

    // alice's first htlc has been failed
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fail]].htlc === htlcs1.head)
    relayerA.expectNoMsg(1 second)

    // bob publishes one of his revoked txs which quickly confirms
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)
    alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobRevokedTx), 100, 1, bobRevokedTx)
    awaitCond(alice.stateName === CLOSING)

    // alice should fail all pending htlcs
    val htlcFails = Seq(
      relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]
    ).map(_.htlc).toSet
    assert(htlcFails === Set(htlcs1(1), htlcs2.head, htlcs2(1)))
    relayerA.expectNoMsg(1 second)
  }

  test("recv BITCOIN_TX_CONFIRMED (one revoked tx, pending htlcs)") { f =>
    testRevokedTxConfirmed(f, ChannelVersion.STANDARD)
  }

  test("recv BITCOIN_TX_CONFIRMED (one revoked tx, pending htlcs, anchor outputs)", Tag("anchor_outputs")) { f =>
    testRevokedTxConfirmed(f, ChannelVersion.ANCHOR_OUTPUTS)
  }

  test("recv ChannelReestablish") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val bobCurrentPerCommitmentPoint = TestConstants.Bob.channelKeyManager.commitmentPoint(
      TestConstants.Bob.channelKeyManager.keyPath(bobCommitments.localParams, bobCommitments.channelVersion),
      bobCommitments.localCommit.index)

    alice ! ChannelReestablish(channelId(bob), 42, 42, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint)

    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === FundingTxSpent(channelId(alice), initialState.spendingTxes.head).getMessage)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, ClosingAlreadyInProgress(channelId(alice))))
  }

}
