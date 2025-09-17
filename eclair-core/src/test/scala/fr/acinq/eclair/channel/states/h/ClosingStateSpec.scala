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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{ConfirmationPriority, ConfirmationTarget, FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT}
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, TimestampSecond, randomBytes32, randomKey}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class ClosingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, alice2relayer: TestProbe, bob2relayer: TestProbe, channelUpdateListener: TestProbe, txListener: TestProbe, eventListener: TestProbe, bobCommitTxs: List[Transaction])

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
    val txListener = TestProbe()
    val eventListener = TestProbe()

    if (unconfirmedFundingTx) {
      within(30 seconds) {
        val channelParams = computeChannelParams(setup, test.tags)
        alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, pushAmount_opt = Some(TestConstants.initiatorPushAmount))
        alice2blockchain.expectMsgType[SetChannelId]
        bob ! channelParams.initChannelBob()
        bob2blockchain.expectMsgType[SetChannelId]
        alice2bob.expectMsgType[OpenChannel]
        alice2bob.forward(bob)
        bob2alice.expectMsgType[AcceptChannel]
        bob2alice.forward(alice)
        alice2bob.expectMsgType[FundingCreated]
        alice2bob.forward(bob)
        bob2alice.expectMsgType[FundingSigned]
        bob2alice.forward(alice)
        alice2blockchain.expectMsgType[SetChannelId]
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[SetChannelId]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
        awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
        awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
        systemA.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        systemA.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        systemA.eventStream.subscribe(eventListener.ref, classOf[ChannelAborted])
        systemB.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        systemB.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        systemB.eventStream.subscribe(eventListener.ref, classOf[ChannelAborted])
        withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, alice2relayer, bob2relayer, channelUpdateListener, txListener, eventListener, Nil)))
      }
    } else {
      within(30 seconds) {
        reachNormal(setup, test.tags)
        if (test.tags.contains(ChannelStateTestsTags.ChannelsPublic) && test.tags.contains(ChannelStateTestsTags.DoNotInterceptGossip)) {
          alice2bob.expectMsgType[AnnouncementSignatures]
          alice2bob.forward(bob)
          alice2bob.expectMsgType[ChannelUpdate]
          bob2alice.expectMsgType[AnnouncementSignatures]
          bob2alice.forward(alice)
          bob2alice.expectMsgType[ChannelUpdate]
        }
        systemA.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        systemA.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        systemB.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        systemB.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        val bobCommitTxs = List(100_000_000 msat, 200_000_000 msat, 300_000_000 msat).flatMap(amt => {
          val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
          crossSign(alice, bob, alice2bob, bob2alice)
          bob2relayer.expectMsgType[RelayForward]
          val bobCommitTx1 = bob.signCommitTx()
          fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
          // alice forwards the fulfill upstream
          alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fulfill]]
          crossSign(bob, alice, bob2alice, alice2bob)
          // bob confirms that it has forwarded the fulfill to alice
          awaitCond(bob.nodeParams.db.pendingCommands.listSettlementCommands(htlc.channelId).isEmpty)
          val bobCommitTx2 = bob.signCommitTx()
          bobCommitTx1 :: bobCommitTx2 :: Nil
        })

        awaitCond(alice.stateName == NORMAL)
        awaitCond(bob.stateName == NORMAL)
        withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, alice2relayer, bob2relayer, channelUpdateListener, txListener, eventListener, bobCommitTxs)))
      }
    }
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectFinalTxPublished("commit-tx")
    alice2blockchain.expectFinalTxPublished("local-main-delayed")
    eventListener.expectMsgType[ChannelAborted]

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
    alice2blockchain.expectFinalTxPublished("commit-tx")
    alice2blockchain.expectFinalTxPublished("local-main-delayed")
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    alice ! BITCOIN_FUNDING_TIMEOUT
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv GetTxResponse (funder, tx found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2bob.expectMsgType[Error]
    val commitTx = alice2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain = alice2blockchain.expectFinalTxPublished("local-main-delayed").tx
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain.txIn.head.outPoint)
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), TimestampSecond.now())
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (funder, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2bob.expectMsgType[Error]
    val commitTx = alice2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain = alice2blockchain.expectFinalTxPublished("local-main-delayed").tx
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain.txIn.head.outPoint)
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    alice2bob.expectNoMessage(100 millis)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == fundingTx) // we republish the funding tx
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    val commitTx = bob2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain = bob2blockchain.expectFinalTxPublished("local-main-delayed").tx
    bob2blockchain.expectWatchTxConfirmed(commitTx.txid)
    bob2blockchain.expectWatchOutputSpent(claimMain.txIn.head.outPoint)
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), TimestampSecond.now())
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    val commitTx = bob2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain = bob2blockchain.expectFinalTxPublished("local-main-delayed").tx
    bob2blockchain.expectWatchTxConfirmed(commitTx.txid)
    bob2blockchain.expectWatchOutputSpent(claimMain.txIn.head.outPoint)
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found, timeout)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    val commitTx = bob2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain = bob2blockchain.expectFinalTxPublished("local-main-delayed").tx
    bob2blockchain.expectWatchTxConfirmed(commitTx.txid)
    bob2blockchain.expectWatchOutputSpent(claimMain.txIn.head.outPoint)
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob.setState(stateData = bob.stateData.asInstanceOf[DATA_CLOSING].copy(waitingSince = bob.nodeParams.currentBlockHeight - Channel.FUNDING_TIMEOUT_FUNDEE - 1))
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == CLOSED)
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, ByteVector32(ByteVector.fill(32)(1)), cltvExpiry = CltvExpiry(300000), onion = TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CMD_FULFILL_HTLC (unexisting htlc)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val c = CMD_FULFILL_HTLC(42, randomBytes32(), None, replyTo_opt = Some(sender.ref))
    alice ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(alice), 42)))
  }

  def testMutualCloseBeforeConverge(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    val sender = TestProbe()
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)
    bob.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(2500 sat)).copy(minimum = FeeratePerKw(250 sat), slow = FeeratePerKw(250 sat)))
    // alice initiates a closing with a low fee
    alice ! CMD_CLOSE(sender.ref, None, Some(ClosingFeerates(FeeratePerKw(500 sat), FeeratePerKw(250 sat), FeeratePerKw(1000 sat))))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    // they don't converge yet, but bob has a publishable commit tx now
    assert(aliceCloseFee != bobCloseFee)
    val Some(mutualCloseTx) = bob.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    // let's make bob publish this closing tx
    bob ! Error(ByteVector32.Zeroes, "")
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx == mutualCloseTx.tx)
    assert(mutualCloseTx == bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last)

    // actual test starts here
    bob ! WatchFundingSpentTriggered(mutualCloseTx.tx)
    bob ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mutualCloseTx.tx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == mutualCloseTx.tx)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv WatchFundingSpentTriggered (mutual close before converging)") { f =>
    testMutualCloseBeforeConverge(f, DefaultCommitmentFormat)
  }

  test("recv WatchFundingSpentTriggered (mutual close before converging, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testMutualCloseBeforeConverge(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (mutual close)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last

    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mutualCloseTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (mutual close, option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val mutualCloseTx = alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].publishedClosingTxs.last

    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mutualCloseTx.tx)
    awaitCond(alice.stateName == CLOSED)

    bob ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mutualCloseTx.tx)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv WatchFundingSpentTriggered (local commit)") { f =>
    import f._
    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.signCommitTx()
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // we are notified afterwards from our watcher about the tx that we just published
    alice ! WatchFundingSpentTriggered(aliceCommitTx)
    assert(alice.stateData == initialState) // this was a no-op
  }

  test("recv WatchFundingSpentTriggered (local commit, public channel)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DoNotInterceptGossip)) { f =>
    import f._

    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    // an error occurs and alice publishes her commit tx
    localClose(alice, alice2blockchain)
    // she notifies the network that the channel shouldn't be used anymore
    inside(listener.expectMsgType[LocalChannelUpdate]) { u => assert(!u.channelUpdate.channelFlags.isEnabled) }
  }

  private def extractPreimageFromClaimHtlcSuccess(f: FixtureParam): Unit = {
    import f._

    // Alice sends htlcs to Bob with the same payment_hash.
    val (preimage, htlc1) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val htlc2 = addHtlc(makeCmdAdd(40_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, preimage)._2, alice, bob, alice2bob, bob2alice)
    assert(htlc1.paymentHash == htlc2.paymentHash)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob has the preimage for those HTLCs, but Alice force-closes before receiving it.
    bob ! CMD_FULFILL_HTLC(htlc1.id, preimage, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc] // ignored
    val (lcp, closingTxs) = localClose(alice, alice2blockchain, htlcTimeoutCount = 2)
    assert(lcp.htlcOutputs.size == 2)
    assert(closingTxs.htlcTimeoutTxs.size == 2)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.contains(lcp))

    // Bob claims the htlc output from Alice's commit tx using its preimage.
    bob ! WatchFundingSpentTriggered(lcp.commitTx)
    initialState.commitments.latest.commitmentFormat match {
      case DefaultCommitmentFormat => ()
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        bob2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
        bob2blockchain.expectFinalTxPublished("remote-main-delayed")
    }
    val claimHtlcSuccessTx1 = bob2blockchain.expectReplaceableTxPublished[ClaimHtlcSuccessTx].sign()
    val claimHtlcSuccessTx2 = bob2blockchain.expectReplaceableTxPublished[ClaimHtlcSuccessTx].sign()
    assert(Seq(claimHtlcSuccessTx1, claimHtlcSuccessTx2).flatMap(_.txIn.map(_.outPoint)).toSet == lcp.htlcOutputs)

    // Alice extracts the preimage and forwards it upstream.
    alice ! WatchOutputSpentTriggered(htlc1.amountMsat.truncateToSatoshi, claimHtlcSuccessTx1)
    Seq(htlc1, htlc2).foreach(htlc => inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]) { fulfill =>
      assert(fulfill.htlc == htlc)
      assert(fulfill.result.paymentPreimage == preimage)
      assert(fulfill.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id))
    })
    assert(alice.stateData == initialState) // this was a no-op

    // The Claim-HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
    alice2blockchain.expectWatchTxConfirmed(claimHtlcSuccessTx1.txid)
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 6, claimHtlcSuccessTx1)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from Claim-HTLC-success tx)") { f =>
    extractPreimageFromClaimHtlcSuccess(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from Claim-HTLC-success tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    extractPreimageFromClaimHtlcSuccess(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from Claim-HTLC-success tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    extractPreimageFromClaimHtlcSuccess(f)
  }

  private def extractPreimageFromHtlcSuccess(f: FixtureParam): Unit = {
    import f._

    // Alice sends htlcs to Bob with the same payment_hash.
    val (preimage, htlc1) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val htlc2 = addHtlc(makeCmdAdd(40_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, preimage)._2, alice, bob, alice2bob, bob2alice)
    assert(htlc1.paymentHash == htlc2.paymentHash)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob has the preimage for those HTLCs, but he force-closes before Alice receives it.
    bob ! CMD_FULFILL_HTLC(htlc1.id, preimage, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc] // ignored
    val (rcp, closingTxs) = localClose(bob, bob2blockchain, htlcSuccessCount = 2)

    // Bob claims the htlc outputs from his own commit tx using its preimage.
    assert(rcp.htlcOutputs.size == 2)
    assert(closingTxs.htlcSuccessTxs.size == 2)

    // Alice extracts the preimage and forwards it upstream.
    alice ! WatchFundingSpentTriggered(rcp.commitTx)
    alice ! WatchOutputSpentTriggered(htlc1.amountMsat.truncateToSatoshi, closingTxs.htlcSuccessTxs.head)
    Seq(htlc1, htlc2).foreach(htlc => inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]) { fulfill =>
      assert(fulfill.htlc == htlc)
      assert(fulfill.result.paymentPreimage == preimage)
      assert(fulfill.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id))
    })

    // The HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 6, closingTxs.htlcSuccessTxs.head)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from HTLC-success tx)") { f =>
    extractPreimageFromHtlcSuccess(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from HTLC-success tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    extractPreimageFromHtlcSuccess(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage from HTLC-success tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    extractPreimageFromHtlcSuccess(f)
  }

  private def extractPreimageFromRemovedHtlc(f: FixtureParam): Unit = {
    import f._

    // Alice sends htlcs to Bob with the same payment_hash.
    val (preimage, htlc1) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val htlc2 = addHtlc(makeCmdAdd(40_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, preimage)._2, alice, bob, alice2bob, bob2alice)
    assert(htlc1.paymentHash == htlc2.paymentHash)
    val (_, htlc3) = addHtlc(60_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val bobStateWithHtlc = bob.stateData.asInstanceOf[DATA_NORMAL]

    // Bob has the preimage for the first two HTLCs, but he fails them instead of fulfilling them.
    failHtlc(htlc1.id, bob, alice, bob2alice, alice2bob)
    failHtlc(htlc2.id, bob, alice, bob2alice, alice2bob)
    failHtlc(htlc3.id, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck] // stop here
    alice2bob.expectMsgType[CommitSig]

    // At that point, the HTLCs are not in Alice's commitment anymore.
    // But Bob has not revoked his commitment yet that contains them.
    bob.setState(NORMAL, bobStateWithHtlc)
    bob ! CMD_FULFILL_HTLC(htlc1.id, preimage, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc] // ignored

    // Bob claims the htlc outputs from his previous commit tx using its preimage.
    val (rcp, closingTxs) = localClose(bob, bob2blockchain, htlcSuccessCount = 2)
    assert(rcp.htlcOutputs.size == 3)
    assert(closingTxs.htlcSuccessTxs.size == 2) // Bob doesn't have the preimage for the last HTLC.

    // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
    alice ! WatchFundingSpentTriggered(rcp.commitTx)
    val (anchorTx_opt, mainTx_opt) = bobStateWithHtlc.commitments.latest.commitmentFormat match {
      case DefaultCommitmentFormat => (None, None)
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        val anchorTx = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
        val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
        (Some(anchorTx), Some(mainTx))
    }
    val claimHtlcTimeoutTxs = Seq(htlc1, htlc2, htlc3).map(_ => alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx])
    assert(claimHtlcTimeoutTxs.map(_.htlcId).toSet == Set(htlc1, htlc2, htlc3).map(_.id))
    alice2blockchain.expectWatchTxConfirmed(rcp.commitTx.txid)
    mainTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.input))
    anchorTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.input.outPoint))
    alice2blockchain.expectWatchOutputsSpent(claimHtlcTimeoutTxs.map(_.input.outPoint))
    alice2blockchain.expectNoMessage(100 millis)

    // Bob's commitment confirms.
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 3, rcp.commitTx)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)

    // Alice extracts the preimage from Bob's HTLC-success and forwards it upstream.
    alice ! WatchOutputSpentTriggered(htlc1.amountMsat.truncateToSatoshi, closingTxs.htlcSuccessTxs.head)
    Seq(htlc1, htlc2).foreach(htlc => inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]) { fulfill =>
      assert(fulfill.htlc == htlc)
      assert(fulfill.result.paymentPreimage == preimage)
      assert(fulfill.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id))
    })
    alice2relayer.expectNoMessage(100 millis)

    // The HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 6, closingTxs.htlcSuccessTxs.head)
    alice2relayer.expectNoMessage(100 millis)

    // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
    val claimHtlcTimeout = claimHtlcTimeoutTxs.find(_.htlcId == htlc3.id).get
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 13, claimHtlcTimeout.tx)
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { fail =>
      assert(fail.htlc == htlc3)
      assert(fail.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc3.id))
    }
  }

  test("recv WatchOutputSpentTriggered (extract preimage for removed HTLC)") { f =>
    extractPreimageFromRemovedHtlc(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage for removed HTLC, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    extractPreimageFromRemovedHtlc(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage for removed HTLC, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    extractPreimageFromRemovedHtlc(f)
  }

  private def extractPreimageFromNextHtlcs(f: FixtureParam): Unit = {
    import f._

    // Alice sends htlcs to Bob with the same payment_hash.
    val (preimage, htlc1) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val htlc2 = addHtlc(makeCmdAdd(40_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, preimage)._2, alice, bob, alice2bob, bob2alice)
    assert(htlc1.paymentHash == htlc2.paymentHash)
    val (_, htlc3) = addHtlc(60_000_000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // We want to test what happens when we stop at that point.
    // But for Bob to create HTLC transaction, he must have received Alice's revocation.
    // So for that sake of the test, we exchange revocation and then reset Alice's state.
    val aliceStateWithoutHtlcs = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice.setState(NORMAL, aliceStateWithoutHtlcs)

    // At that point, the HTLCs are not in Alice's commitment yet.
    val (rcp, closingTxs) = localClose(bob, bob2blockchain)
    assert(rcp.htlcOutputs.size == 3)
    // Bob doesn't have the preimage yet for any of those HTLCs.
    assert(closingTxs.htlcTxs.isEmpty)
    // Bob receives the preimage for the first two HTLCs.
    bob ! CMD_FULFILL_HTLC(htlc1.id, preimage, None)
    val htlcSuccessTxs = aliceStateWithoutHtlcs.commitments.latest.commitmentFormat match {
      case DefaultCommitmentFormat => (0 until 2).map(_ => bob2blockchain.expectFinalTxPublished("htlc-success").tx)
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        val htlcSuccess = (0 until 2).map(_ => bob2blockchain.expectReplaceableTxPublished[HtlcSuccessTx])
        assert(htlcSuccess.map(_.htlcId).toSet == Set(htlc1.id, htlc2.id))
        htlcSuccess.map(_.sign())
    }
    bob2blockchain.expectNoMessage(100 millis)
    val batchHtlcSuccessTx = Transaction(2, htlcSuccessTxs.flatMap(_.txIn), htlcSuccessTxs.flatMap(_.txOut), 0)

    // Alice prepares Claim-HTLC-timeout transactions for each HTLC.
    alice ! WatchFundingSpentTriggered(rcp.commitTx)
    val (anchorTx_opt, mainTx_opt) = aliceStateWithoutHtlcs.commitments.latest.commitmentFormat match {
      case DefaultCommitmentFormat => (None, None)
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        val anchorTx = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
        val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
        (Some(anchorTx), Some(mainTx))
    }
    val claimHtlcTimeoutTxs = Seq(htlc1, htlc2, htlc3).map(_ => alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx])
    assert(claimHtlcTimeoutTxs.map(_.htlcId).toSet == Set(htlc1, htlc2, htlc3).map(_.id))
    alice2blockchain.expectWatchTxConfirmed(rcp.commitTx.txid)
    mainTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.input))
    anchorTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.input.outPoint))
    alice2blockchain.expectWatchOutputsSpent(claimHtlcTimeoutTxs.map(_.input.outPoint))
    alice2blockchain.expectNoMessage(100 millis)

    // Bob's commitment confirms.
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 3, rcp.commitTx)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)

    // Alice extracts the preimage from Bob's batched HTLC-success and forwards it upstream.
    alice ! WatchOutputSpentTriggered(htlc1.amountMsat.truncateToSatoshi, batchHtlcSuccessTx)
    alice2blockchain.expectWatchTxConfirmed(batchHtlcSuccessTx.txid)
    Seq(htlc1, htlc2).foreach(htlc => inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]) { fulfill =>
      assert(fulfill.htlc == htlc)
      assert(fulfill.result.paymentPreimage == preimage)
      assert(fulfill.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id))
    })

    // The HTLC-success transaction confirms: nothing to do, preimage has already been relayed.
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 6, batchHtlcSuccessTx)
    alice2relayer.expectNoMessage(100 millis)

    // Alice's Claim-HTLC-timeout transaction confirms: we relay the failure upstream.
    val claimHtlcTimeout = claimHtlcTimeoutTxs.find(_.htlcId == htlc3.id).get
    alice ! WatchTxConfirmedTriggered(alice.nodeParams.currentBlockHeight, 13, claimHtlcTimeout.tx)
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { fail =>
      assert(fail.htlc == htlc3)
      assert(fail.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc3.id))
    }
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchOutputSpentTriggered (extract preimage for next batch of HTLCs)") { f =>
    extractPreimageFromNextHtlcs(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage for next batch of HTLCs, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    extractPreimageFromNextHtlcs(f)
  }

  test("recv WatchOutputSpentTriggered (extract preimage for next batch of HTLCs, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    extractPreimageFromNextHtlcs(f)
  }

  test("recv CMD_BUMP_FORCE_CLOSE_FEE (local commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val (localCommitPublished, closingTxs) = localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.nonEmpty)
    assert(closingTxs.anchorTx_opt.nonEmpty)

    val replyTo = TestProbe()
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    inside(alice2blockchain.expectMsgType[PublishReplaceableTx]) { publish =>
      assert(publish.txInfo.isInstanceOf[ClaimLocalAnchorTx])
      assert(publish.commitTx == localCommitPublished.commitTx)
      assert(publish.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    }
  }

  def testLocalCommitTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[LocalCommitConfirmed])
    systemA.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])

    // alice sends an htlc to bob
    val (_, htlca1) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends an htlc below dust to bob
    val amountBelowDust = alice.commitments.latest.localCommitParams.dustLimit - 100.msat
    val (_, htlca2) = addHtlc(amountBelowDust, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (closingState, closingTxs) = localClose(alice, alice2blockchain, htlcTimeoutCount = 1)

    // actual test starts here
    assert(closingState.localOutput_opt.isDefined)
    assert(closingTxs.mainTx_opt.isDefined)
    assert(closingState.htlcOutputs.size == 1)
    assert(closingTxs.htlcTimeoutTxs.size == 1)
    val htlcTimeoutTx = closingTxs.htlcTimeoutTxs.head
    assert(closingState.htlcDelayedOutputs.isEmpty)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingState.commitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == closingState.commitTx)
    assert(listener.expectMsgType[LocalCommitConfirmed].refundAtBlock == BlockHeight(42) + alice.commitments.latest.localCommitParams.toSelfDelay.toInt)
    assert(listener.expectMsgType[PaymentSettlingOnChain].paymentHash == htlca1.paymentHash)
    // htlcs below dust will never reach the chain, once the commit tx is confirmed we can consider them failed
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { settled =>
      assert(settled.htlc == htlca2)
      assert(settled.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlca2.id))
    }
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(200), 0, closingTxs.mainTx_opt.get)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcTimeoutTx)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.irrevocablySpent.values.toSet == Set(closingState.commitTx, closingTxs.mainTx_opt.get, htlcTimeoutTx))
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { settled =>
      assert(settled.htlc == htlca1)
      assert(settled.origin == alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlca1.id))
    }
    alice2relayer.expectNoMessage(100 millis)

    // We claim the htlc-delayed output now that the HTLC tx has been confirmed.
    val htlcDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    assert(htlcDelayedTx.input == OutPoint(htlcTimeoutTx, 0))
    Transaction.correctlySpends(htlcDelayedTx.tx, Seq(htlcTimeoutTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
    alice ! WatchOutputSpentTriggered(0 sat, htlcDelayedTx.tx)
    alice2blockchain.expectWatchTxConfirmed(htlcDelayedTx.tx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.htlcDelayedOutputs.size == 1)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, htlcDelayedTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit)") { f =>
    testLocalCommitTxConfirmed(f, DefaultCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (local commit, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testLocalCommitTxConfirmed(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (local commit, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaprootPhoenix)) { f =>
    testLocalCommitTxConfirmed(f, PhoenixSimpleTaprootChannelCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (local commit with multiple htlcs for the same payment)") { f =>
    import f._

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(30_000_000 msat, alice, bob, alice2bob, bob2alice)
    // and more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(25_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(30_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    val amountBelowDust = alice.commitments.latest.localCommitParams.dustLimit - 100.msat
    val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val dust = addHtlc(dustCmd, alice, bob, alice2bob, bob2alice)
    val (_, cmd4) = makeCmdAdd(20_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight + 1, ra1)
    val htlca4 = addHtlc(cmd4, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (closingState, closingTxs) = localClose(alice, alice2blockchain, htlcTimeoutCount = 4)

    // actual test starts here
    assert(closingState.localOutput_opt.isDefined)
    assert(closingTxs.mainTx_opt.isDefined)
    assert(closingState.htlcOutputs.size == 4)
    assert(closingTxs.htlcTimeoutTxs.size == 4)
    assert(closingState.htlcDelayedOutputs.isEmpty)

    // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 1, closingState.commitTx)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == dust)
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(200), 0, closingTxs.mainTx_opt.get)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 0, closingTxs.htlcTimeoutTxs(3))
    val forwardedFail4 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3, forwardedFail4) == Set(htlca1, htlca2, htlca3, htlca4))
    alice2relayer.expectNoMessage(100 millis)

    val htlcDelayedTxs = closingTxs.htlcTimeoutTxs.map(htlcTx => {
      val htlcDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
      assert(htlcDelayedTx.input == OutPoint(htlcTx, 0))
      alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
      htlcDelayedTx
    })
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.htlcDelayedOutputs.size == 4)
    htlcDelayedTxs.foreach(tx => {
      alice ! WatchOutputSpentTriggered(0 sat, tx.tx)
      alice2blockchain.expectWatchTxConfirmed(tx.tx.txid)
    })
    htlcDelayedTxs.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(203), 0, tx.tx))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit with htlcs only signed by local)") { f =>
    import f._
    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val aliceCommitTx = alice.signCommitTx()
    // alice sends an htlc
    val (_, htlc) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    // and signs it (but bob doesn't sign it)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    // note that bob doesn't receive the new sig!
    // then we make alice unilaterally close the channel
    val (closingState, closingTxs) = localClose(alice, alice2blockchain)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcOutputs.isEmpty && closingState.htlcDelayedOutputs.isEmpty)
    assert(closingTxs.htlcTxs.isEmpty)
    // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, aliceCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (local commit with htlcs only signed by remote)") { f =>
    import f._
    // Bob sends an htlc and signs it.
    addHtlc(75_000_000 msat, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2relayer.expectNoMessage(100 millis) // the HTLC is not relayed downstream
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcRemoteSigs.size == 1)
    val aliceCommitTx = alice.signCommitTx()
    // Note that alice has not signed the htlc yet!
    // We make her unilaterally close the channel.
    val (closingState, closingTxs) = localClose(alice, alice2blockchain)

    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcOutputs.isEmpty && closingState.htlcDelayedOutputs.isEmpty)
    assert(closingTxs.htlcTxs.isEmpty)
    // Alice should ignore the htlc (she hasn't relayed it yet): it is Bob's responsibility to claim it.
    // Once the commit tx and her main output are confirmed, she can consider the channel closed.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, aliceCommitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with htlcs not relayed)") { f =>
    import f._
    // Bob sends an htlc and signs it.
    addHtlc(75_000_000 msat, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not received by Alice
    alice2relayer.expectNoMessage(100 millis) // the HTLC is not relayed downstream
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcRemoteSigs.size == 1)
    val bobCommitTx = bob.signCommitTx()
    // We make Bob unilaterally close the channel.
    val (rcp, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)

    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(rcp.htlcOutputs.isEmpty)
    assert(closingTxs.htlcTxs.isEmpty)
    // Alice should ignore the htlc (she hasn't relayed it yet): it is Bob's responsibility to claim it.
    // Once the commit tx and her main output are confirmed, she can consider the channel closed.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit with fulfill only signed by local)") { f =>
    import f._
    // bob sends an htlc
    val (r, htlc) = addHtlc(110_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc)
    val aliceCommitTx = alice.signCommitTx()
    assert(aliceCommitTx.txOut.size == 3) // 2 main outputs + 1 htlc

    // alice fulfills the HTLC but bob doesn't receive the signature
    alice ! CMD_FULFILL_HTLC(htlc.id, r, None, commit = true)
    alice2bob.expectMsgType[UpdateFulfillHtlc]
    alice2bob.forward(bob)
    inside(bob2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fulfill]]) { settled =>
      assert(settled.htlc == htlc)
      assert(settled.result.paymentPreimage == r)
      assert(settled.origin == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.originChannels(htlc.id))
    }
    alice2bob.expectMsgType[CommitSig]
    // note that bob doesn't receive the new sig!
    // then we make alice unilaterally close the channel
    val (closingState, _) = localClose(alice, alice2blockchain, htlcSuccessCount = 1)
    assert(closingState.commitTx.txid == aliceCommitTx.txid)
  }

  test("recv WatchTxConfirmedTriggered (local commit with fail not acked by remote)") { f =>
    import f._
    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val (_, htlc) = addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    failHtlc(htlc.id, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    // note that alice doesn't receive the last revocation
    // then we make alice unilaterally close the channel
    val (closingState, closingTxs) = localClose(alice, alice2blockchain)
    assert(closingState.commitTx.txOut.length == 2) // htlc has been removed

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcOutputs.isEmpty && closingState.htlcDelayedOutputs.isEmpty)
    assert(closingTxs.htlcTxs.isEmpty)
    // when the commit tx is confirmed, alice knows that the htlc will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.commitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (local commit followed by htlc settlement)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // Bob sends 2 HTLCs to Alice that will be settled during the force-close: one will be fulfilled, the other will be failed.
    val (r1, htlc1) = addHtlc(75_000_000 msat, CltvExpiryDelta(48), bob, alice, bob2alice, alice2bob)
    val (_, htlc2) = addHtlc(65_000_000 msat, CltvExpiryDelta(36), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc1)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc2)

    // Alice force-closes.
    val (closingState, closingTxs) = localClose(alice, alice2blockchain)
    assert(closingState.commitTx.txOut.length == 6) // 2 main outputs + 2 anchor outputs + 2 htlcs
    assert(closingState.localOutput_opt.nonEmpty)
    assert(closingState.htlcOutputs.size == 2)
    assert(closingTxs.htlcTxs.isEmpty) // we don't have the preimage to claim the htlc-success yet

    // Alice's commitment and main transaction confirm: she waits for the HTLC outputs to be spent.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.commitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))
    assert(alice.stateName == CLOSING)

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, None, commit = true)
    val htlcSuccess = alice2blockchain.expectReplaceableTxPublished[HtlcSuccessTx](ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))
    assert(htlcSuccess.preimage == r1)
    Transaction.correctlySpends(htlcSuccess.sign(), closingState.commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice receives a failure for the second HTLC from downstream; she can stop watching the corresponding HTLC output.
    alice ! CMD_FAIL_HTLC(htlc2.id, FailureReason.EncryptedDownstreamFailure(ByteVector.empty, None), None)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice restarts before the HTLC transaction confirmed.
    val beforeRestart1 = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart1)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)
    // Alice republishes the HTLC-success transaction, which then confirms.
    assert(alice2blockchain.expectReplaceableTxPublished[HtlcSuccessTx].input == htlcSuccess.input)
    closingTxs.anchorTx_opt.foreach(anchorTx => alice2blockchain.expectWatchOutputSpent(anchorTx.txIn.head.outPoint))
    alice2blockchain.expectWatchOutputSpent(htlcSuccess.input.outPoint)
    alice ! WatchOutputSpentTriggered(htlcSuccess.amountIn, htlcSuccess.tx)
    alice2blockchain.expectWatchTxConfirmed(htlcSuccess.tx.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, htlcSuccess.tx)
    // Alice publishes a 3rd-stage HTLC transaction.
    val htlcDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    assert(htlcDelayedTx.input == OutPoint(htlcSuccess.tx, 0))
    alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice restarts again before the 3rd-stage HTLC transaction confirmed.
    val beforeRestart2 = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart2)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)
    closingTxs.anchorTx_opt.foreach(anchorTx => alice2blockchain.expectWatchOutputSpent(anchorTx.txIn.head.outPoint))
    // Alice republishes the 3rd-stage HTLC transaction, which then confirms.
    alice2blockchain.expectFinalTxPublished(htlcDelayedTx.tx.txid)
    alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
    alice ! WatchOutputSpentTriggered(0 sat, htlcDelayedTx.tx)
    alice2blockchain.expectWatchTxConfirmed(htlcDelayedTx.tx.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, htlcDelayedTx.tx)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (local commit)") { f =>
    import f._

    // alice sends an htlc to bob
    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (closingState, closingTxs) = localClose(alice, alice2blockchain, htlcTimeoutCount = 1)
    assert(closingTxs.mainTx_opt.nonEmpty)
    val htlcTimeoutTx = closingTxs.htlcTimeoutTxs.head

    // simulate a node restart after a feerate increase that exceeds our max-closing-feerate
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    assert(alice.nodeParams.onChainFeeConf.maxClosingFeerate == FeeratePerKw(15_000 sat))
    alice.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(20_000 sat)))
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    alice2blockchain.expectFinalTxPublished(closingState.commitTx.txid)
    // we increase the feerate of our main transaction, but cap it to our max-closing-feerate
    val mainTx2 = closingTxs.mainTx_opt.map(_ => alice2blockchain.expectFinalTxPublished("local-main-delayed")).get
    assert(mainTx2.tx.txOut.head.amount < closingTxs.mainTx_opt.get.txOut.head.amount)
    val mainFeerate = Transactions.fee2rate(mainTx2.fee, mainTx2.tx.weight())
    assert(FeeratePerKw(14_500 sat) <= mainFeerate && mainFeerate <= FeeratePerKw(15_500 sat))
    assert(alice2blockchain.expectFinalTxPublished("htlc-timeout").input == htlcTimeoutTx.txIn.head.outPoint)
    alice2blockchain.expectWatchTxConfirmed(closingState.commitTx.txid)
    closingTxs.mainTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.txIn.head.outPoint))
    alice2blockchain.expectWatchOutputSpent(htlcTimeoutTx.txIn.head.outPoint)

    // the htlc transaction confirms, so we publish a 3rd-stage transaction
    alice ! WatchTxConfirmedTriggered(BlockHeight(2701), 1, closingState.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(2702), 0, htlcTimeoutTx)
    val htlcDelayed = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    alice2blockchain.expectWatchOutputSpent(htlcDelayed.input)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.htlcDelayedOutputs.nonEmpty)
    val beforeSecondRestart = alice.stateData.asInstanceOf[DATA_CLOSING]

    // simulate another node restart
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeSecondRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // we should re-publish unconfirmed transactions
    closingTxs.mainTx_opt.foreach(mainTx => {
      alice2blockchain.expectFinalTxPublished("local-main-delayed")
      alice2blockchain.expectWatchOutputSpent(mainTx.txIn.head.outPoint)
    })
    assert(alice2blockchain.expectFinalTxPublished("htlc-delayed").input == htlcDelayed.input)
    alice2blockchain.expectWatchOutputSpent(htlcDelayed.input)
    // the main transaction confirms
    closingTxs.mainTx_opt.foreach(mainTx => alice ! WatchTxConfirmedTriggered(BlockHeight(2801), 5, mainTx))
    assert(alice.stateName == CLOSING)
    // the htlc delayed transaction confirms
    alice ! WatchTxConfirmedTriggered(BlockHeight(2802), 5, htlcDelayed.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (local commit with htlc-delayed transactions)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    // Alice has one incoming and one outgoing HTLC.
    addHtlc(75_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (preimage, incomingHtlc) = addHtlc(80_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Alice force-closes.
    val (closingState1, closingTxs) = localClose(alice, alice2blockchain, htlcTimeoutCount = 1)
    assert(closingTxs.mainTx_opt.nonEmpty)
    val htlcTimeoutTx = closingTxs.htlcTimeoutTxs.head

    // The commit tx confirms.
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingState1.commitTx)
    closingTxs.anchorTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(42), 1, tx))
    alice2blockchain.expectNoMessage(100 millis)

    // Alice receives the preimage for the incoming HTLC.
    alice ! CMD_FULFILL_HTLC(incomingHtlc.id, preimage, None, commit = true)
    val htlcSuccess = alice2blockchain.expectReplaceableTxPublished[HtlcSuccessTx]
    assert(htlcSuccess.preimage == preimage)
    alice2blockchain.expectNoMessage(100 millis)

    // The HTLC txs confirms, so we publish 3rd-stage txs.
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcTimeoutTx)
    val htlcTimeoutDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    assert(htlcTimeoutDelayedTx.input == OutPoint(htlcTimeoutTx, 0))
    alice2blockchain.expectWatchOutputSpent(htlcTimeoutDelayedTx.input)
    Transaction.correctlySpends(htlcTimeoutDelayedTx.tx, Seq(htlcTimeoutTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcSuccess.tx)
    val htlcSuccessDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    assert(htlcSuccessDelayedTx.input == OutPoint(htlcSuccess.tx, 0))
    alice2blockchain.expectWatchOutputSpent(htlcSuccessDelayedTx.input)
    Transaction.correctlySpends(htlcSuccessDelayedTx.tx, Seq(htlcSuccess.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // We simulate a node restart after a feerate increase.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(15_000 sat)))
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // We re-publish closing transactions with a higher feerate.
    val mainTx = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    assert(mainTx.tx.txOut.head.amount < closingTxs.mainTx_opt.get.txOut.head.amount)
    alice2blockchain.expectWatchOutputSpent(mainTx.input)
    val htlcDelayedTxs = Seq(
      alice2blockchain.expectFinalTxPublished("htlc-delayed"),
      alice2blockchain.expectFinalTxPublished("htlc-delayed"),
    )
    assert(htlcDelayedTxs.map(_.input).toSet == Seq(htlcTimeoutDelayedTx, htlcSuccessDelayedTx).map(_.input).toSet)
    assert(htlcDelayedTxs.flatMap(_.tx.txOut.map(_.amount)).sum < Seq(htlcTimeoutDelayedTx, htlcSuccessDelayedTx).flatMap(_.tx.txOut.map(_.amount)).sum)
    alice2blockchain.expectWatchOutputsSpent(htlcDelayedTxs.map(_.input))

    // We replay the HTLC fulfillment: nothing happens since we already published a 3rd-stage transaction.
    alice ! CMD_FULFILL_HTLC(incomingHtlc.id, preimage, None, commit = true)
    alice2blockchain.expectNoMessage(100 millis)

    // The remaining transactions confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 0, mainTx.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 1, htlcTimeoutDelayedTx.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 2, htlcSuccessDelayedTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (htlcs claimed by both local and remote)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // Alice and Bob each sends 3 HTLCs:
    //  - one of them will be fulfilled and claimed with the preimage on-chain
    //  - one of them will be fulfilled but will lose the race with the htlc-timeout on-chain
    //  - the other will be timed out on-chain
    val (r1a, htlc1a) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (r2a, htlc2a) = addHtlc(55_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(60_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (r1b, htlc1b) = addHtlc(75_000_000 msat, bob, alice, bob2alice, alice2bob)
    val (r2b, htlc2b) = addHtlc(55_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(40_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob has the preimage for 2 of the 3 HTLCs he received.
    bob ! CMD_FULFILL_HTLC(htlc1a.id, r1a, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_FULFILL_HTLC(htlc2a.id, r2a, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    // Alice has the preimage for 2 of the 3 HTLCs she received.
    alice ! CMD_FULFILL_HTLC(htlc1b.id, r1b, None)
    alice2bob.expectMsgType[UpdateFulfillHtlc]
    alice ! CMD_FULFILL_HTLC(htlc2b.id, r2b, None)
    alice2bob.expectMsgType[UpdateFulfillHtlc]

    // Alice force-closes.
    val (closingStateAlice, closingTxsAlice) = localClose(alice, alice2blockchain, htlcSuccessCount = 2, htlcTimeoutCount = 3)
    assert(closingStateAlice.htlcOutputs.size == 6)
    assert(closingTxsAlice.htlcSuccessTxs.size == 2)
    assert(closingTxsAlice.htlcTimeoutTxs.size == 3)

    // Bob detects Alice's force-close.
    val (closingStateBob, closingTxsBob) = remoteClose(closingStateAlice.commitTx, bob, bob2blockchain, htlcSuccessCount = 2, htlcTimeoutCount = 3)
    assert(closingStateBob.htlcOutputs.size == 6)
    assert(closingTxsBob.htlcSuccessTxs.size == 2)
    assert(closingTxsBob.htlcTimeoutTxs.size == 3)

    // The commit transaction and main transactions confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_000), 3, closingStateAlice.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_000), 5, closingTxsAlice.anchorTx_opt.get)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_001), 1, closingTxsAlice.mainTx_opt.get)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_001), 2, closingTxsBob.mainTx_opt.get)
    alice2blockchain.expectNoMessage(100 millis)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_000), 3, closingStateAlice.commitTx)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_001), 1, closingTxsAlice.mainTx_opt.get)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_001), 2, closingTxsBob.mainTx_opt.get)
    bob2blockchain.expectNoMessage(100 millis)

    // One of Alice's HTLC-success transactions confirms.
    val htlcSuccessAlice = closingTxsAlice.htlcSuccessTxs.head
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_005), 0, htlcSuccessAlice)
    val htlcDelayed1 = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    Transaction.correctlySpends(htlcDelayed1.tx, Seq(htlcSuccessAlice), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchOutputSpent(htlcDelayed1.input)
    alice2blockchain.expectNoMessage(100 millis)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_005), 0, htlcSuccessAlice)
    bob2blockchain.expectNoMessage(100 millis)

    // One of Bob's HTLC-success transactions confirms.
    val htlcSuccessBob = closingTxsBob.htlcSuccessTxs.last
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_008), 13, htlcSuccessBob)
    alice2blockchain.expectNoMessage(100 millis)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_008), 13, htlcSuccessBob)
    bob2blockchain.expectNoMessage(100 millis)

    // Alice and Bob have two remaining HTLC-timeout transactions, one of which conflicts with an HTLC-success transaction.
    val htlcTimeoutTxsAlice = closingTxsAlice.htlcTimeoutTxs.filter(_.txIn.head.outPoint != htlcSuccessBob.txIn.head.outPoint)
    assert(htlcTimeoutTxsAlice.size == 2)
    val htlcTimeoutTxsBob = closingTxsBob.htlcTimeoutTxs.filter(_.txIn.head.outPoint != htlcSuccessAlice.txIn.head.outPoint)
    assert(htlcTimeoutTxsBob.size == 2)
    val htlcTimeoutTxBob1 = htlcTimeoutTxsBob.find(_.txIn.head.outPoint == closingTxsAlice.htlcSuccessTxs.last.txIn.head.outPoint).get
    val htlcTimeoutTxBob2 = htlcTimeoutTxsBob.find(_.txIn.head.outPoint != closingTxsAlice.htlcSuccessTxs.last.txIn.head.outPoint).get

    // Bob's HTLC-timeout transaction which conflicts with Alice's HTLC-success transaction confirms.
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_008), 13, htlcTimeoutTxBob1)
    alice2blockchain.expectNoMessage(100 millis)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_008), 13, htlcTimeoutTxBob1)
    bob2blockchain.expectNoMessage(100 millis)
    val remainingHtlcOutputs = htlcTimeoutTxBob2.txIn.head.outPoint +: htlcTimeoutTxsAlice.map(_.txIn.head.outPoint)

    // We simulate a node restart after a feerate decrease.
    Seq(alice, bob).foreach { peer =>
      val beforeRestart = peer.stateData.asInstanceOf[DATA_CLOSING]
      peer.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(2500 sat)))
      peer.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
      peer ! INPUT_RESTORED(beforeRestart)
      awaitCond(peer.stateName == CLOSING)
    }
    Seq(alice2blockchain, bob2blockchain).foreach(_.expectMsgType[SetChannelId])

    // Alice re-publishes closing transactions: her remaining HTLC-success transaction has been double-spent, so she
    // only has HTLC-timeout transactions left.
    val republishedHtlcTxsAlice = (1 to 2).map(_ => alice2blockchain.expectReplaceableTxPublished[HtlcTimeoutTx])
    alice2blockchain.expectWatchOutputsSpent(remainingHtlcOutputs)
    assert(republishedHtlcTxsAlice.map(_.input.outPoint).toSet == htlcTimeoutTxsAlice.map(_.txIn.head.outPoint).toSet)
    assert(alice2blockchain.expectFinalTxPublished("htlc-delayed").input == htlcDelayed1.input)
    alice2blockchain.expectWatchOutputSpent(htlcDelayed1.input)
    alice2blockchain.expectNoMessage(100 millis)

    // Bob re-publishes closing transactions: he has 1 HTLC-success and 1 HTLC-timeout transactions left.
    val republishedHtlcTxsBob = (1 to 2).map(_ => bob2blockchain.expectMsgType[PublishReplaceableTx])
    bob2blockchain.expectWatchOutputsSpent(remainingHtlcOutputs ++ closingTxsBob.anchorTx_opt.map(_.txIn.head.outPoint).toSeq)
    assert(republishedHtlcTxsBob.map(_.input).toSet == Set(htlcTimeoutTxBob2.txIn.head.outPoint, closingTxsBob.htlcSuccessTxs.head.txIn.head.outPoint))
    bob2blockchain.expectNoMessage(100 millis)

    // Bob's previous HTLC-timeout transaction confirms.
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_009), 21, htlcTimeoutTxBob2)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_009), 21, htlcTimeoutTxBob2)

    // Alice's re-published HTLC-timeout transactions confirm.
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_009), 25, republishedHtlcTxsAlice.head.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_009), 25, republishedHtlcTxsAlice.head.tx)
    val htlcDelayed2 = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    alice2blockchain.expectWatchOutputSpent(htlcDelayed2.input)
    assert(alice.stateName == CLOSING)
    assert(bob.stateName == CLOSING)
    bob ! WatchTxConfirmedTriggered(BlockHeight(750_009), 26, republishedHtlcTxsAlice.last.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_009), 26, republishedHtlcTxsAlice.last.tx)
    val htlcDelayed3 = alice2blockchain.expectFinalTxPublished("htlc-delayed")
    alice2blockchain.expectWatchOutputSpent(htlcDelayed3.input)
    assert(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSED)

    // Alice's 3rd-stage transactions confirm.
    Seq(htlcDelayed1, htlcDelayed2, htlcDelayed3).foreach(p => alice ! WatchTxConfirmedTriggered(BlockHeight(750_100), 0, p.tx))
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with htlcs only signed by local in next remote commit)") { f =>
    import f._
    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val bobCommitTx = bob.signCommitTx()
    // alice sends an htlc
    val (_, htlc) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    // and signs it (but bob doesn't sign it)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    val (closingState, _) = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.localOutput_opt.isEmpty)
    assert(closingState.htlcOutputs.isEmpty)
    // when the commit tx is signed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit with settled htlcs)") { f =>
    import f._

    // alice sends two htlcs to bob
    val upstream1 = localUpstream()
    val (preimage1, htlc1) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice, upstream1)
    val upstream2 = localUpstream()
    val (_, htlc2) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice, upstream2)
    crossSign(alice, bob, alice2bob, bob2alice)

    // bob fulfills one HTLC and fails the other one without revoking its previous commitment.
    fulfillHtlc(htlc1.id, preimage1, bob, alice, bob2alice, alice2bob)
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFulfill]]) { settled =>
      assert(settled.htlc == htlc1)
      assert(settled.origin.upstream == upstream1)
    }
    failHtlc(htlc2.id, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not sent to alice

    // bob closes the channel using his latest commitment, which doesn't contain any htlc.
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcRemoteSigs.isEmpty)
    val commitTx = bob.signCommitTx()
    alice ! WatchFundingSpentTriggered(commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, commitTx)
    // the two HTLCs have been overridden by the on-chain commit
    // the first one is a no-op since we already relayed the fulfill upstream
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { settled =>
      assert(settled.htlc == htlc1)
      assert(settled.origin.upstream == upstream1)
    }
    inside(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]) { settled =>
      assert(settled.htlc == htlc2)
      assert(settled.origin.upstream == upstream2)
    }
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.localOutput_opt.isEmpty)
    assert(closingState.htlcOutputs.isEmpty)
    assert(closingTxs.mainTx_opt.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
  }

  test("recv WatchFundingSpentTriggered (remote commit, public channel)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DoNotInterceptGossip)) { f =>
    import f._

    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    // bob publishes his commit tx
    val bobCommitTx = bob.signCommitTx()
    remoteClose(bobCommitTx, alice, alice2blockchain)
    // alice notifies the network that the channel shouldn't be used anymore
    inside(listener.expectMsgType[LocalChannelUpdate]) { u => assert(!u.channelUpdate.channelFlags.isEnabled) }
  }

  test("recv WatchFundingSpentTriggered (remote commit, option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    // Bob publishes his last current commit tx, the one it had when entering NEGOTIATING state.
    val bobCommitTx = bobCommitTxs.last
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.htlcOutputs.isEmpty)
    assert(closingTxs.mainTx_opt.isEmpty)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
  }

  test("recv CMD_BUMP_FORCE_CLOSE_FEE (remote commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val bobCommitTx = bobCommitTxs.last
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.anchorOutput_opt.nonEmpty)
    assert(closingTxs.anchorTx_opt.nonEmpty)

    val replyTo = TestProbe()
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    inside(alice2blockchain.expectMsgType[PublishReplaceableTx]) { publish =>
      assert(publish.txInfo.isInstanceOf[ClaimRemoteAnchorTx])
      assert(publish.commitTx == bobCommitTx)
      assert(publish.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    }
  }

  test("recv WatchTxConfirmedTriggered (remote commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last
    assert(bobCommitTx.txOut.size == 4) // two main outputs + two anchors
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.localOutput_opt.nonEmpty)
    assert(closingTxs.mainTx_opt.nonEmpty)
    assert(closingState.htlcOutputs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    txListener.expectMsgType[TransactionPublished]
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingTxs.mainTx_opt.get)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    assert(alice.commitments.latest.commitmentFormat == DefaultCommitmentFormat)
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice won't create a claimMainOutputTx because her main output is already spendable by the wallet
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.localOutput_opt.isEmpty)
    assert(alice.stateName == CLOSING)
    // once the remote commit is confirmed the channel is definitively closed
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.commitments.latest.commitmentFormat == ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last
    assert(bobCommitTx.txOut.size == 4) // two main outputs + two anchors
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.localOutput_opt.nonEmpty)
    assert(closingTxs.mainTx_opt.nonEmpty)
    assert(closingState.htlcOutputs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingTxs.mainTx_opt.get)
    awaitCond(alice.stateName == CLOSED)
  }

  def testRemoteCommitTxWithHtlcsConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(15_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(20_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight - 1, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob publishes the latest commit tx.
    val bobCommitTx = bob.signCommitTx()
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommitTx.txOut.length == 7) // two main outputs + two anchors + 3 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 5) // two main outputs + 3 HTLCs
    }
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain, htlcTimeoutCount = 3)
    assert(closingState.htlcOutputs.size == 3)
    assert(closingTxs.htlcTimeoutTxs.length == 3)

    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    // for static_remote_key channels there is no claimMainOutputTx (bob's commit tx directly sends to our wallet)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == Set(htlca1, htlca2, htlca3))
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment)") { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, DefaultCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (remote commit) followed by htlc settlement", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // Bob sends 2 HTLCs to Alice that will be settled during the force-close: one will be fulfilled, the other will be failed.
    val (r1, htlc1) = addHtlc(110_000_000 msat, CltvExpiryDelta(48), bob, alice, bob2alice, alice2bob)
    val (_, htlc2) = addHtlc(60_000_000 msat, CltvExpiryDelta(36), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc1)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc2)

    // Alice sends an HTLC to Bob: Bob has two spendable commit txs.
    val (_, htlc3) = addHtlc(95_000_000 msat, CltvExpiryDelta(144), alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig] // We stop here: Alice sent her CommitSig, but doesn't hear back from Bob.

    // Now Bob publishes the first commit tx (force-close).
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.length == 6) // 2 main outputs + 2 anchor outputs + 2 HTLCs
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.localOutput_opt.nonEmpty)
    assert(closingState.htlcOutputs.size == 2)
    assert(closingTxs.htlcTxs.isEmpty) // we don't have the preimage to claim the htlc-success yet

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, None, commit = true)
    val htlcSuccess = alice2blockchain.expectReplaceableTxPublished[ClaimHtlcSuccessTx](ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))
    assert(htlcSuccess.preimage == r1)
    Transaction.correctlySpends(htlcSuccess.sign(), bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectNoMessage(100 millis)

    // Bob's commitment confirms: the third htlc was not included in the commit tx published on-chain, so we can consider it failed.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    closingTxs.anchorTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 1, tx))
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc3)
    // Alice's main transaction confirms.
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))

    // Alice receives a failure for the second HTLC from downstream; she can stop watching the corresponding HTLC output.
    alice ! CMD_FAIL_HTLC(htlc2.id, FailureReason.EncryptedDownstreamFailure(ByteVector.empty, None), None)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice restarts, and pending transactions confirm.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)
    // Alice republishes the HTLC-success transaction, which then confirms.
    assert(alice2blockchain.expectReplaceableTxPublished[ClaimHtlcSuccessTx].input == htlcSuccess.input)
    alice2blockchain.expectWatchOutputSpent(htlcSuccess.input.outPoint)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, htlcSuccess.tx)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (remote commit)") { f =>
    import f._

    // Alice sends an htlc to Bob: Bob then force-closes.
    val (_, htlc) = addHtlc(50_000_000 msat, CltvExpiryDelta(24), alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val bobCommitTx = bob.signCommitTx()
    val (_, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain, htlcTimeoutCount = 1)
    assert(closingTxs.htlcTimeoutTxs.size == 1)
    val htlcTimeoutTx = closingTxs.htlcTxs.head
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)

    // We simulate a node restart with a lower feerate.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(2_500 sat)))
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // We should re-publish unconfirmed transactions.
    // Our main transaction should have a lower feerate.
    // HTLC transactions are unchanged: the feerate will be based on their expiry.
    closingTxs.mainTx_opt.foreach(tx => {
      val tx2 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
      assert(tx2.tx.txOut.head.amount > tx.txOut.head.amount)
      alice2blockchain.expectWatchOutputSpent(tx.txIn.head.outPoint)
    })
    val htlcTimeout = alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx](ConfirmationTarget.Absolute(htlc.cltvExpiry.blockHeight))
    assert(htlcTimeout.input.outPoint == htlcTimeoutTx.txIn.head.outPoint)
    assert(htlcTimeout.tx.txid == htlcTimeoutTx.txid)
    alice2blockchain.expectWatchOutputSpent(htlcTimeout.input.outPoint)
  }

  private def testNextRemoteCommitTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): (Transaction, PublishedForceCloseTxs, Set[UpdateAddHtlc]) = {
    import f._

    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(20_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // The last one is only signed by Alice: Bob has two spendable commit tx.
    val (_, cmd3) = makeCmdAdd(20_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not forwarded to Alice (malicious Bob)
    bob2alice.expectMsgType[CommitSig] // not forwarded to Alice (malicious Bob)

    // Bob publishes the next commit tx.
    val bobCommitTx = bob.signCommitTx()
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommitTx.txOut.length == 7) // two main outputs + two anchors + 3 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 5) // two main outputs + 3 HTLCs
    }
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain, htlcTimeoutCount = 3)
    assert(closingState.htlcOutputs.size == 3)
    assert(closingTxs.htlcTimeoutTxs.size == 3)
    (bobCommitTx, closingTxs, Set(htlca1, htlca2, htlca3))
  }

  test("recv WatchTxConfirmedTriggered (next remote commit)") { f =>
    import f._
    val (bobCommitTx, closingTxs, htlcs) = testNextRemoteCommitTxConfirmed(f, DefaultCommitmentFormat)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val (bobCommitTx, closingTxs, htlcs) = testNextRemoteCommitTxConfirmed(f, DefaultCommitmentFormat)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    assert(closingTxs.mainTx_opt.isEmpty) // with static_remotekey we don't claim out main output
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val (bobCommitTx, closingTxs, htlcs) = testNextRemoteCommitTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    val (bobCommitTx, closingTxs, htlcs) = testNextRemoteCommitTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, closingTxs.htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, closingTxs.htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, closingTxs.htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit) followed by htlc settlement", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // Bob sends 2 HTLCs to Alice that will be settled during the force-close: one will be fulfilled, the other will be failed.
    val (r1, htlc1) = addHtlc(110_000_000 msat, CltvExpiryDelta(64), bob, alice, bob2alice, alice2bob)
    val (_, htlc2) = addHtlc(70_000_000 msat, CltvExpiryDelta(96), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc1)
    assert(alice2relayer.expectMsgType[RelayForward].add == htlc2)

    // Alice sends an HTLC to Bob: Bob has two spendable commit txs.
    val (_, htlc3) = addHtlc(95_000_000 msat, CltvExpiryDelta(32), alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not forwarded to Alice (malicious Bob)
    bob2alice.expectMsgType[CommitSig] // not forwarded to Alice (malicious Bob)

    // Now Bob publishes the next commit tx (force-close).
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.length == 7) // 2 main outputs + 2 anchor outputs + 3 HTLCs
    val (closingState, closingTxs) = remoteClose(bobCommitTx, alice, alice2blockchain, htlcTimeoutCount = 1)
    assert(closingState.localOutput_opt.nonEmpty)
    assert(closingState.htlcOutputs.size == 3)
    assert(closingTxs.htlcTxs.size == 1) // we don't have the preimage to claim the htlc-success yet
    val htlcTimeoutTx = closingTxs.htlcTimeoutTxs.head

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, None, commit = true)
    val htlcSuccess = alice2blockchain.expectReplaceableTxPublished[ClaimHtlcSuccessTx](ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))
    assert(htlcSuccess.preimage == r1)
    Transaction.correctlySpends(htlcSuccess.sign(), bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectNoMessage(100 millis)

    // Bob's commitment and Alice's main transaction confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    closingTxs.anchorTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, tx))

    // Alice receives a failure for the second HTLC from downstream; she can stop watching the corresponding HTLC output.
    alice ! CMD_FAIL_HTLC(htlc2.id, FailureReason.EncryptedDownstreamFailure(ByteVector.empty, None), None)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice restarts, and pending HTLC transactions confirm.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)
    // Alice republishes the HTLC transactions, which then confirm.
    val htlcTx1 = alice2blockchain.expectMsgType[PublishReplaceableTx]
    val htlcTx2 = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(Set(htlcTx1.input, htlcTx2.input) == Set(htlcTimeoutTx.txIn.head.outPoint, htlcSuccess.input.outPoint))
    alice2blockchain.expectWatchOutputsSpent(Seq(htlcTx1.input, htlcTx2.input))
    alice2blockchain.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, htlcSuccess.tx)
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, htlcTimeoutTx)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc3)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (next remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val (bobCommitTx, closingTxs, _) = testNextRemoteCommitTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    val anchorTx = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    closingTxs.mainTx_opt.foreach(_ => alice2blockchain.expectFinalTxPublished("remote-main-delayed"))
    val htlcTimeoutTxs = closingTxs.htlcTxs.map(_ => alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx])
    assert(htlcTimeoutTxs.map(_.input.outPoint).toSet == closingTxs.htlcTxs.map(_.txIn.head.outPoint).toSet)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    closingTxs.mainTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.txIn.head.outPoint))
    alice2blockchain.expectWatchOutputSpent(anchorTx.input.outPoint)
    alice2blockchain.expectWatchOutputsSpent(htlcTimeoutTxs.map(_.input.outPoint))
  }

  private def testFutureRemoteCommitTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Transaction = {
    import f._
    val oldStateData = alice.stateData
    assert(oldStateData.asInstanceOf[DATA_NORMAL].commitments.latest.commitmentFormat == commitmentFormat)
    // This HTLC will be fulfilled.
    val (ra1, htlca1) = addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    // These 2 HTLCs should timeout on-chain, but since alice lost data, she won't be able to claim them.
    val (ra2, _) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (_, cmd) = makeCmdAdd(15_000_000 msat, bob.nodeParams.nodeId, alice.nodeParams.currentBlockHeight, ra2)
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
    val aliceInit = Init(TestConstants.Alice.nodeParams.features.initFeatures())
    val bobInit = Init(TestConstants.Bob.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == PleasePublishYourCommitment(channelId(alice)).getMessage)
    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)
    // bob is nice and publishes its commitment
    val bobCommitTx = bob.signCommitTx()
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommitTx.txOut.length == 6) // two main outputs + two anchors + 2 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 4) // two main outputs + 2 HTLCs
    }
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    bobCommitTx
  }

  test("recv WatchTxConfirmedTriggered (future remote commit)") { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, DefaultCommitmentFormat)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
    // bob's commit tx sends directly to alice's wallet
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)
    alice2blockchain.expectNoMessage(100 millis) // alice ignores the htlc-timeout

    // actual test starts here
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (future remote commit, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, DefaultCommitmentFormat)
    // using option_static_remotekey alice doesn't need to sweep her output
    awaitCond(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // after the commit tx is confirmed the channel is closed, no claim transactions needed
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (future remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    // alice is able to claim its main output
    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(mainTx.tx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(mainTx.input)
    alice2blockchain.expectNoMessage(100 millis) // alice ignores the htlc-timeout
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)

    // actual test starts here
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mainTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (future remote commit, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
    // alice is able to claim its main output
    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(mainTx.tx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(mainTx.input)
    alice2blockchain.expectNoMessage(100 millis) // alice ignores the htlc-timeout
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)

    // actual test starts here
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mainTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (future remote commit)") { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, DefaultCommitmentFormat)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    awaitCond(alice.stateName == CLOSING)

    // bob's commit tx sends funds directly to our wallet
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  case class RevokedCommit(commitTx: Transaction, htlcTxs: Seq[UnsignedHtlcTx])

  case class RevokedCloseFixture(bobRevokedTxs: Seq[RevokedCommit], htlcsAlice: Seq[(UpdateAddHtlc, ByteVector32)], htlcsBob: Seq[(UpdateAddHtlc, ByteVector32)])

  private def prepareRevokedClose(f: FixtureParam, commitmentFormat: CommitmentFormat): RevokedCloseFixture = {
    import f._

    // Bob's first commit tx doesn't contain any htlc
    val bobCommit1 = RevokedCommit(bob.signCommitTx(), Nil)
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommit1.commitTx.txOut.size == 4) // 2 main outputs + 2 anchors
      case DefaultCommitmentFormat => assert(bobCommit1.commitTx.txOut.size == 2) // 2 main outputs
    }

    // Bob's second commit tx contains 1 incoming htlc and 1 outgoing htlc
    val (bobCommit2, htlcAlice1, htlcBob1) = {
      val (ra, htlcAlice) = addHtlc(35_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (rb, htlcBob) = addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val bobCommit2 = RevokedCommit(bob.signCommitTx(), bob.htlcTxs())
      (bobCommit2, (htlcAlice, ra), (htlcBob, rb))
    }

    assert(alice.signCommitTx().txOut.size == bobCommit2.commitTx.txOut.size)
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommit2.commitTx.txOut.size == 6)
      case DefaultCommitmentFormat => assert(bobCommit2.commitTx.txOut.size == 4)
    }

    // Bob's third commit tx contains 2 incoming htlcs and 2 outgoing htlcs
    val (bobCommit3, htlcAlice2, htlcBob2) = {
      val (ra, htlcAlice) = addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (rb, htlcBob) = addHtlc(18_000_000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val bobCommit3 = RevokedCommit(bob.signCommitTx(), bob.htlcTxs())
      (bobCommit3, (htlcAlice, ra), (htlcBob, rb))
    }

    assert(alice.signCommitTx().txOut.size == bobCommit3.commitTx.txOut.size)
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommit3.commitTx.txOut.size == 8)
      case DefaultCommitmentFormat => assert(bobCommit3.commitTx.txOut.size == 6)
    }

    // Bob's fourth commit tx doesn't contain any htlc
    val bobCommit4 = {
      Seq(htlcAlice1, htlcAlice2).foreach { case (htlcAlice, _) => failHtlc(htlcAlice.id, bob, alice, bob2alice, alice2bob) }
      Seq(htlcBob1, htlcBob2).foreach { case (htlcBob, _) => failHtlc(htlcBob.id, alice, bob, alice2bob, bob2alice) }
      crossSign(alice, bob, alice2bob, bob2alice)
      RevokedCommit(bob.signCommitTx(), Nil)
    }

    assert(alice.signCommitTx().txOut.size == bobCommit4.commitTx.txOut.size)
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(bobCommit4.commitTx.txOut.size == 4)
      case DefaultCommitmentFormat => assert(bobCommit4.commitTx.txOut.size == 2)
    }

    RevokedCloseFixture(Seq(bobCommit1, bobCommit2, bobCommit3, bobCommit4), Seq(htlcAlice1, htlcAlice2), Seq(htlcBob1, htlcBob2))
  }

  case class RevokedCloseTxs(mainTx_opt: Option[Transaction], mainPenaltyTx: Transaction, htlcPenaltyTxs: Seq[Transaction])

  private def setupFundingSpentRevokedTx(f: FixtureParam, commitmentFormat: CommitmentFormat): (Transaction, RevokedCloseTxs) = {
    import f._

    val revokedCloseFixture = prepareRevokedClose(f, commitmentFormat)
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    // bob publishes one of his revoked txs
    val bobRevokedTx = revokedCloseFixture.bobRevokedTxs(1).commitTx
    alice ! WatchFundingSpentTriggered(bobRevokedTx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedTx)
    if (alice.stateData.asInstanceOf[DATA_CLOSING].channelParams.localParams.walletStaticPaymentBasepoint.isEmpty) {
      assert(rvk.localOutput_opt.nonEmpty)
    }
    assert(rvk.remoteOutput_opt.nonEmpty)
    assert(rvk.htlcOutputs.size == 2)
    assert(rvk.htlcDelayedOutputs.isEmpty)

    // alice publishes the penalty txs
    val mainTx_opt = if (alice.stateData.asInstanceOf[DATA_CLOSING].channelParams.localParams.walletStaticPaymentBasepoint.isEmpty) Some(alice2blockchain.expectFinalTxPublished("remote-main-delayed")) else None
    val mainPenaltyTx = alice2blockchain.expectFinalTxPublished("main-penalty")
    Transaction.correctlySpends(mainPenaltyTx.tx, bobRevokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val htlcPenaltyTxs = (0 until 2).map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
    assert(htlcPenaltyTxs.map(_.input).toSet == rvk.htlcOutputs)
    htlcPenaltyTxs.foreach(penaltyTx => Transaction.correctlySpends(penaltyTx.tx, bobRevokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    // alice spends all outpoints of the revoked tx, except her main output when it goes directly to our wallet
    val spentOutpoints = mainTx_opt.map(_.input) ++ Seq(mainPenaltyTx.input) ++ htlcPenaltyTxs.map(_.input)
    commitmentFormat match {
      case DefaultCommitmentFormat if mainTx_opt.isEmpty => assert(spentOutpoints.size == bobRevokedTx.txOut.size - 1) // we don't claim our main output, it directly goes to our wallet
      case DefaultCommitmentFormat => assert(spentOutpoints.size == bobRevokedTx.txOut.size)
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(spentOutpoints.size == bobRevokedTx.txOut.size - 2) // we don't claim the anchors
    }

    // alice watches on-chain transactions
    alice2blockchain.expectWatchTxConfirmed(bobRevokedTx.txid)
    alice2blockchain.expectWatchOutputsSpent(spentOutpoints.toSeq)
    alice2blockchain.expectNoMessage(100 millis)

    (bobRevokedTx, RevokedCloseTxs(mainTx_opt.map(_.tx), mainPenaltyTx.tx, htlcPenaltyTxs.map(_.tx)))
  }

  private def testFundingSpentRevokedTx(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val (bobRevokedTx, closingTxs) = setupFundingSpentRevokedTx(f, commitmentFormat)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobRevokedTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the revoked commit

    // once all txs are confirmed, alice can move to the closed state
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, bobRevokedTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobRevokedTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 1, closingTxs.mainPenaltyTx)
    closingTxs.mainTx_opt.foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(110), 2, tx))
    closingTxs.htlcPenaltyTxs.dropRight(1).foreach(tx => alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, tx))
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, closingTxs.htlcPenaltyTxs.last)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    testFundingSpentRevokedTx(f, DefaultCommitmentFormat)
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testFundingSpentRevokedTx(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testFundingSpentRevokedTx(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testFundingSpentRevokedTx(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv WatchFundingSpentTriggered (multiple revoked tx)", Tag(ChannelStateTestsTags.OptionSimpleTaprootPhoenix)) { f =>
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
    assert(revokedCloseFixture.bobRevokedTxs.map(_.commitTx.txid).toSet.size == revokedCloseFixture.bobRevokedTxs.size) // all commit txs are distinct

    def broadcastBobRevokedTx(revokedTx: Transaction, htlcCount: Int, revokedCount: Int): RevokedCloseTxs = {
      alice ! WatchFundingSpentTriggered(revokedTx)
      awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == revokedCount)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.last.commitTx == revokedTx)

      // alice publishes penalty txs
      val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
      val mainPenalty = alice2blockchain.expectFinalTxPublished("main-penalty")
      val htlcPenaltyTxs = (1 to htlcCount).map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
      (mainTx.tx +: mainPenalty.tx +: htlcPenaltyTxs.map(_.tx)).foreach(tx => Transaction.correctlySpends(tx, revokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
      alice2blockchain.expectWatchTxConfirmed(revokedTx.txid)
      alice2blockchain.expectWatchOutputsSpent(mainTx.input +: mainPenalty.input +: htlcPenaltyTxs.map(_.input))
      alice2blockchain.expectNoMessage(100 millis)

      RevokedCloseTxs(Some(mainTx.tx), mainPenalty.tx, htlcPenaltyTxs.map(_.tx))
    }

    // bob publishes a first revoked tx (no htlc in that commitment)
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs.head.commitTx, 0, 1)
    // bob publishes a second revoked tx
    val closingTxs = broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(1).commitTx, 2, 2)
    // bob publishes a third revoked tx
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(2).commitTx, 4, 3)

    // bob's second revoked tx confirms: once all penalty txs are confirmed, alice can move to the closed state
    // NB: if multiple txs confirm in the same block, we may receive the events in any order
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 1, closingTxs.mainPenaltyTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 2, closingTxs.mainTx_opt.get)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, revokedCloseFixture.bobRevokedTxs(1).commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, closingTxs.htlcPenaltyTxs(0))
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, closingTxs.htlcPenaltyTxs(1))
    awaitCond(alice.stateName == CLOSED)
  }

  def testInputRestoredRevokedTx(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val (bobRevokedTx, closingTxs) = setupFundingSpentRevokedTx(f, commitmentFormat)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    closingTxs.mainTx_opt.foreach(_ => alice2blockchain.expectFinalTxPublished("remote-main-delayed"))
    assert(alice2blockchain.expectFinalTxPublished("main-penalty").input == closingTxs.mainPenaltyTx.txIn.head.outPoint)
    val htlcPenaltyTxs = closingTxs.htlcPenaltyTxs.map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
    assert(htlcPenaltyTxs.map(_.input).toSet == closingTxs.htlcPenaltyTxs.map(_.txIn.head.outPoint).toSet)
    alice2blockchain.expectWatchTxConfirmed(bobRevokedTx.txid)
    closingTxs.mainTx_opt.foreach(tx => alice2blockchain.expectWatchOutputSpent(tx.txIn.head.outPoint))
    alice2blockchain.expectWatchOutputSpent(closingTxs.mainPenaltyTx.txIn.head.outPoint)
    alice2blockchain.expectWatchOutputsSpent(htlcPenaltyTxs.map(_.input))
  }

  test("recv INPUT_RESTORED (one revoked tx)") { f =>
    testInputRestoredRevokedTx(f, DefaultCommitmentFormat)
  }

  test("recv INPUT_RESTORED (one revoked tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testInputRestoredRevokedTx(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_RESTORED (one revoked tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testInputRestoredRevokedTx(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_RESTORED (one revoked tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testInputRestoredRevokedTx(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testRevokedHtlcTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, commitmentFormat)
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    // bob publishes one of his revoked txs
    val bobRevokedCommit = revokedCloseFixture.bobRevokedTxs(2)
    alice ! WatchFundingSpentTriggered(bobRevokedCommit.commitTx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedCommit.commitTx)
    if (alice.stateData.asInstanceOf[DATA_CLOSING].channelParams.localParams.walletStaticPaymentBasepoint.nonEmpty) {
      assert(rvk.localOutput_opt.isEmpty)
    } else {
      assert(rvk.localOutput_opt.nonEmpty)
    }
    assert(rvk.remoteOutput_opt.nonEmpty)
    assert(rvk.htlcOutputs.size == 4)
    assert(rvk.htlcDelayedOutputs.isEmpty)

    // alice publishes the penalty txs and watches outputs
    val mainTx_opt = if (alice.stateData.asInstanceOf[DATA_CLOSING].channelParams.localParams.walletStaticPaymentBasepoint.isEmpty) Some(alice2blockchain.expectFinalTxPublished("remote-main-delayed")) else None
    val mainPenalty = alice2blockchain.expectFinalTxPublished("main-penalty")
    val htlcPenalty = (1 to 4).map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
    alice2blockchain.expectWatchTxConfirmed(rvk.commitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(mainTx_opt.map(_.input).toSeq ++ Seq(mainPenalty.input) ++ htlcPenalty.map(_.input))
    alice2blockchain.expectNoMessage(100 millis)

    // the revoked commit and main penalty transactions confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, rvk.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 0, mainPenalty.tx)
    mainTx_opt.foreach(p => alice ! WatchTxConfirmedTriggered(BlockHeight(110), 1, p.tx))

    // bob publishes one of his HTLC-success transactions
    val (fulfilledHtlc, _) = revokedCloseFixture.htlcsAlice.head
    val bobHtlcSuccessTx1 = bobRevokedCommit.htlcTxs.collectFirst { case txInfo: UnsignedHtlcSuccessTx if txInfo.htlcId == fulfilledHtlc.id => txInfo }.get
    assert(bobHtlcSuccessTx1.paymentHash == fulfilledHtlc.paymentHash)
    alice ! WatchOutputSpentTriggered(bobHtlcSuccessTx1.amountIn, bobHtlcSuccessTx1.tx)
    alice2blockchain.expectWatchTxConfirmed(bobHtlcSuccessTx1.tx.txid)

    // bob publishes one of his HTLC-timeout transactions
    val (failedHtlc, _) = revokedCloseFixture.htlcsBob.last
    val bobHtlcTimeoutTx = bobRevokedCommit.htlcTxs.collectFirst { case txInfo: UnsignedHtlcTimeoutTx if txInfo.htlcId == failedHtlc.id => txInfo }.get
    assert(bobHtlcTimeoutTx.paymentHash == failedHtlc.paymentHash)
    alice ! WatchOutputSpentTriggered(bobHtlcTimeoutTx.amountIn, bobHtlcTimeoutTx.tx)
    alice2blockchain.expectWatchTxConfirmed(bobHtlcTimeoutTx.tx.txid)

    // bob RBFs his htlc-success with a different transaction
    val bobHtlcSuccessTx2 = bobHtlcSuccessTx1.tx.copy(txIn = TxIn(OutPoint(randomTxId(), 0), Nil, 0) +: bobHtlcSuccessTx1.tx.txIn)
    assert(bobHtlcSuccessTx2.txid !== bobHtlcSuccessTx1.tx.txid)
    alice ! WatchOutputSpentTriggered(bobHtlcSuccessTx1.amountIn, bobHtlcSuccessTx2)
    alice2blockchain.expectWatchTxConfirmed(bobHtlcSuccessTx2.txid)

    // bob's HTLC-timeout confirms: alice reacts by publishing a penalty tx
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, bobHtlcTimeoutTx.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.htlcDelayedOutputs.size == 1)
    val htlcTimeoutDelayedPenalty = alice2blockchain.expectFinalTxPublished("htlc-delayed-penalty")
    Transaction.correctlySpends(htlcTimeoutDelayedPenalty.tx, bobHtlcTimeoutTx.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchOutputSpent(htlcTimeoutDelayedPenalty.input)
    alice2blockchain.expectNoMessage(100 millis)

    // bob's htlc-success RBF confirms: alice reacts by publishing a penalty tx
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 1, bobHtlcSuccessTx2)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.htlcDelayedOutputs.size == 2)
    val htlcSuccessDelayedPenalty = alice2blockchain.expectFinalTxPublished("htlc-delayed-penalty")
    Transaction.correctlySpends(htlcSuccessDelayedPenalty.tx, bobHtlcSuccessTx2 :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchOutputSpent(htlcSuccessDelayedPenalty.input)
    alice2blockchain.expectNoMessage(100 millis)

    // transactions confirm: alice can move to the closed state
    val bobHtlcOutpoints = Set(bobHtlcTimeoutTx.input.outPoint, bobHtlcSuccessTx1.input.outPoint)
    val remainingHtlcPenaltyTxs = htlcPenalty.filterNot(tx => bobHtlcOutpoints.contains(tx.input))
    assert(remainingHtlcPenaltyTxs.size == 2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 2, remainingHtlcPenaltyTxs.head.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, remainingHtlcPenaltyTxs.last.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(120), 0, htlcTimeoutDelayedPenalty.tx)
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(121), 0, htlcSuccessDelayedPenalty.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (revoked htlc-success tx, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    testRevokedHtlcTxConfirmed(f, DefaultCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked htlc-success tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testRevokedHtlcTxConfirmed(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked htlc-success tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRevokedHtlcTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked htlc-success tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testRevokedHtlcTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testRevokedAggregatedHtlcTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    // bob publishes one of his revoked txs
    val revokedCloseFixture = prepareRevokedClose(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val bobRevokedCommit = revokedCloseFixture.bobRevokedTxs(2)
    alice ! WatchFundingSpentTriggered(bobRevokedCommit.commitTx)
    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedCommit.commitTx)
    assert(rvk.htlcOutputs.size == 4)
    assert(rvk.htlcDelayedOutputs.isEmpty)

    // alice publishes the penalty txs and watches outputs
    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    val mainPenalty = alice2blockchain.expectFinalTxPublished("main-penalty")
    val htlcPenalty = (1 to 4).map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
    alice2blockchain.expectWatchTxConfirmed(rvk.commitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(mainTx.input, mainPenalty.input) ++ htlcPenalty.map(_.input))
    alice2blockchain.expectNoMessage(100 millis)

    // bob claims multiple htlc outputs in a single transaction (this is possible with anchor outputs because signatures
    // use sighash_single | sighash_anyonecanpay)
    val bobHtlcTxs = bobRevokedCommit.htlcTxs.collect {
      case txInfo: UnsignedHtlcSuccessTx =>
        val preimage = revokedCloseFixture.htlcsAlice.collectFirst { case (add, preimage) if add.id == txInfo.htlcId => preimage }.get
        assert(Crypto.sha256(preimage) == txInfo.paymentHash)
        txInfo
      case txInfo: UnsignedHtlcTimeoutTx =>
        txInfo
    }
    assert(bobHtlcTxs.map(_.input.outPoint).size == 4)
    val bobHtlcTx = Transaction(
      2,
      Seq(
        TxIn(OutPoint(randomTxId(), 4), Nil, 1), // utxo used for fee bumping
        bobHtlcTxs(0).tx.txIn.head,
        bobHtlcTxs(1).tx.txIn.head,
        bobHtlcTxs(2).tx.txIn.head,
        bobHtlcTxs(3).tx.txIn.head
      ),
      Seq(
        TxOut(10000 sat, Script.pay2wpkh(randomKey().publicKey)), // change output
        bobHtlcTxs(0).tx.txOut.head,
        bobHtlcTxs(1).tx.txOut.head,
        bobHtlcTxs(2).tx.txOut.head,
        bobHtlcTxs(3).tx.txOut.head
      ),
      0
    )

    // alice reacts by publishing penalty txs that spend bob's htlc transaction
    alice ! WatchOutputSpentTriggered(bobHtlcTxs(0).amountIn, bobHtlcTx)
    alice2blockchain.expectWatchTxConfirmed(bobHtlcTx.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(129), 7, bobHtlcTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.htlcDelayedOutputs.size == 4)
    val htlcDelayedPenalty = (1 to 4).map(_ => alice2blockchain.expectFinalTxPublished("htlc-delayed-penalty"))
    val spentOutpoints = Seq(OutPoint(bobHtlcTx, 1), OutPoint(bobHtlcTx, 2), OutPoint(bobHtlcTx, 3), OutPoint(bobHtlcTx, 4))
    assert(htlcDelayedPenalty.map(_.input).toSet == spentOutpoints.toSet)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.htlcDelayedOutputs == spentOutpoints.toSet)
    htlcDelayedPenalty.foreach(penalty => Transaction.correctlySpends(penalty.tx, bobHtlcTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectWatchOutputsSpent(spentOutpoints)
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (revoked aggregated htlc tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRevokedAggregatedHtlcTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked aggregated htlc tx, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testRevokedAggregatedHtlcTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testInputRestoredRevokedHtlcTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    // Bob publishes one of his revoked txs.
    alice.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(2_500 sat)))
    val revokedCloseFixture = prepareRevokedClose(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val bobRevokedCommit = revokedCloseFixture.bobRevokedTxs(2)
    val commitTx = bobRevokedCommit.commitTx
    alice ! WatchFundingSpentTriggered(commitTx)
    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)

    // Alice publishes the penalty txs and watches outputs.
    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    val mainPenalty = alice2blockchain.expectFinalTxPublished("main-penalty")
    val htlcPenalty = (1 to 4).map(_ => alice2blockchain.expectFinalTxPublished("htlc-penalty"))
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(mainTx.input, mainPenalty.input) ++ htlcPenalty.map(_.input))
    alice ! WatchTxConfirmedTriggered(BlockHeight(700_000), 2, commitTx)
    alice2blockchain.expectNoMessage(100 millis)

    // Bob claims HTLC outputs using aggregated transactions.
    val bobHtlcTxs = bobRevokedCommit.htlcTxs
    assert(bobHtlcTxs.map(_.input.outPoint).size == 4)
    val bobHtlcTx1 = Transaction(
      2,
      Seq(
        TxIn(OutPoint(randomTxId(), 4), Nil, 1), // utxo used for fee bumping
        bobHtlcTxs(0).tx.txIn.head,
        TxIn(OutPoint(randomTxId(), 4), Nil, 1), // unrelated utxo
        bobHtlcTxs(1).tx.txIn.head,
      ),
      Seq(
        TxOut(10_000 sat, Script.pay2wpkh(randomKey().publicKey)), // change output
        bobHtlcTxs(0).tx.txOut.head,
        TxOut(15_000 sat, Script.pay2wpkh(randomKey().publicKey)), // unrelated output
        bobHtlcTxs(1).tx.txOut.head,
      ),
      0
    )
    val bobHtlcTx2 = Transaction(
      2,
      Seq(
        bobHtlcTxs(2).tx.txIn.head,
        bobHtlcTxs(3).tx.txIn.head,
        TxIn(OutPoint(randomTxId(), 0), Nil, 1), // utxo used for fee bumping
      ),
      Seq(
        bobHtlcTxs(2).tx.txOut.head,
        bobHtlcTxs(3).tx.txOut.head,
        TxOut(20_000 sat, Script.pay2wpkh(randomKey().publicKey)), // change output
      ),
      0
    )

    // Alice reacts by publishing penalty txs that spend bob's htlc transactions.
    val htlcDelayedPenalty = Seq(bobHtlcTx1, bobHtlcTx2).flatMap(bobHtlcTx => {
      alice ! WatchOutputSpentTriggered(bobHtlcTxs(0).amountIn, bobHtlcTx)
      alice2blockchain.expectWatchTxConfirmed(bobHtlcTx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(700_004), 7, bobHtlcTx)
      val htlcDelayedPenalty = (1 to 2).map(_ => alice2blockchain.expectFinalTxPublished("htlc-delayed-penalty"))
      alice2blockchain.expectWatchOutputsSpent(htlcDelayedPenalty.map(_.input))
      htlcDelayedPenalty.foreach(penalty => Transaction.correctlySpends(penalty.tx, bobHtlcTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
      htlcDelayedPenalty
    })
    assert(htlcDelayedPenalty.map(_.input).toSet == Set(OutPoint(bobHtlcTx1, 1), OutPoint(bobHtlcTx1, 3), OutPoint(bobHtlcTx2, 0), OutPoint(bobHtlcTx2, 1)))
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.htlcDelayedOutputs.size == 4)

    // We simulate a node restart after a feerate increase.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(5_000 sat)))
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // We re-publish closing transactions with a higher feerate.
    val mainTx2 = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    assert(mainTx2.input == mainTx.input)
    assert(mainTx2.tx.txOut.head.amount < mainTx.tx.txOut.head.amount)
    Transaction.correctlySpends(mainTx2.tx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val mainPenalty2 = alice2blockchain.expectFinalTxPublished("main-penalty")
    assert(mainPenalty2.input == mainPenalty.input)
    assert(mainPenalty2.tx.txOut.head.amount < mainPenalty.tx.txOut.head.amount)
    Transaction.correctlySpends(mainPenalty2.tx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchOutputsSpent(Seq(mainTx2.input, mainPenalty2.input))
    val htlcDelayedPenalty2 = (1 to 4).map(_ => alice2blockchain.expectFinalTxPublished("htlc-delayed-penalty"))
    alice2blockchain.expectWatchOutputsSpent(htlcDelayedPenalty2.map(_.input))
    assert(htlcDelayedPenalty2.map(_.input).toSet == htlcDelayedPenalty.map(_.input).toSet)
    assert(htlcDelayedPenalty2.map(_.tx.txOut.head.amount).sum < htlcDelayedPenalty.map(_.tx.txOut.head.amount).sum)
    htlcDelayedPenalty2.foreach {
      case txInfo if txInfo.input.txid == bobHtlcTx1.txid => Transaction.correctlySpends(txInfo.tx, Seq(bobHtlcTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      case txInfo => Transaction.correctlySpends(txInfo.tx, Seq(bobHtlcTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    // The remaining transactions confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(700_009), 18, mainTx.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(700_011), 11, mainPenalty2.tx)
    // Some of the updated HTLC-delayed penalty transactions confirm.
    htlcDelayedPenalty2.take(3).foreach(p => alice ! WatchTxConfirmedTriggered(BlockHeight(700_015), 0, p.tx))
    assert(alice.stateName == CLOSING)
    // The last HTLC-delayed penalty to confirm is the previous version with a lower feerate.
    htlcDelayedPenalty.filter(p => !htlcDelayedPenalty2.take(3).map(_.input).contains(p.input)).foreach(p => alice ! WatchTxConfirmedTriggered(BlockHeight(700_016), 0, p.tx))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (revoked htlc transactions confirmed)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testInputRestoredRevokedHtlcTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_RESTORED (revoked htlc transactions confirmed, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testInputRestoredRevokedHtlcTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  private def testRevokedTxConfirmed(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    assert(alice.commitments.latest.commitmentFormat == commitmentFormat)
    val initOutputCount = commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => 4
      case DefaultCommitmentFormat => 2
    }
    assert(bob.signCommitTx().txOut.size == initOutputCount)

    // bob's second commit tx contains 2 incoming htlcs
    val (bobRevokedTx, htlcs1) = {
      val (_, htlc1) = addHtlc(35_000_000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc2) = addHtlc(20_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx = bob.signCommitTx()
      assert(bobCommitTx.txOut.size == initOutputCount + 2)
      (bobCommitTx, Seq(htlc1, htlc2))
    }

    // bob's third commit tx contains 1 of the previous htlcs and 2 new htlcs
    val htlcs2 = {
      val (_, htlc3) = addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc4) = addHtlc(18_000_000 msat, alice, bob, alice2bob, bob2alice)
      failHtlc(htlcs1.head.id, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      assert(bob.signCommitTx().txOut.size == initOutputCount + 3)
      Seq(htlc3, htlc4)
    }

    // alice's first htlc has been failed
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fail]].htlc == htlcs1.head)
    alice2relayer.expectNoMessage(100 millis)

    // bob publishes one of his revoked txs which quickly confirms
    alice ! WatchFundingSpentTriggered(bobRevokedTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 1, bobRevokedTx)
    awaitCond(alice.stateName == CLOSING)

    // alice should fail all pending htlcs
    val htlcFails = Seq(
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]
    ).map(f => (f.htlc, f.origin)).toSet
    val expectedFails = Set(htlcs1(1), htlcs2(0), htlcs2(1)).map(htlc => (htlc, alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)))
    assert(htlcFails == expectedFails)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (revoked commit tx, pending htlcs)") { f =>
    testRevokedTxConfirmed(f, DefaultCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked commit tx, pending htlcs, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testRevokedTxConfirmed(f, UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked commit tx, pending htlcs, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRevokedTxConfirmed(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv WatchTxConfirmedTriggered (revoked commit tx, pending htlcs, taproot)", Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testRevokedTxConfirmed(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv ChannelReestablish") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    val bobCommitments = bob.stateData.asInstanceOf[DATA_CLOSING].commitments
    val bobCurrentPerCommitmentPoint = bob.underlyingActor.channelKeys.commitmentPoint(bobCommitments.localCommitIndex)
    alice ! ChannelReestablish(channelId(bob), 42, 42, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint)
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == FundingTxSpent(channelId(alice), initialState.spendingTxs.head.txid).getMessage)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, ClosingAlreadyInProgress(channelId(alice))))
  }

  test("recv CMD_FORCECLOSE (max_closing_feerate override)") { f =>
    import f._

    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].maxClosingFeerate_opt.isEmpty)
    val commitTx = alice2blockchain.expectFinalTxPublished("commit-tx").tx
    val claimMain1 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain1.input)

    alice ! CMD_FORCECLOSE(sender.ref, maxClosingFeerate_opt = Some(FeeratePerKw(5_000 sat)))
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].maxClosingFeerate_opt.contains(FeeratePerKw(5_000 sat)))
    alice2blockchain.expectFinalTxPublished(commitTx.txid)
    val claimMain2 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain2.input)
    assert(claimMain2.fee != claimMain1.fee)

    alice ! CMD_FORCECLOSE(sender.ref, maxClosingFeerate_opt = Some(FeeratePerKw(10_000 sat)))
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].maxClosingFeerate_opt.contains(FeeratePerKw(10_000 sat)))
    alice2blockchain.expectFinalTxPublished(commitTx.txid)
    val claimMain3 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    assert(claimMain2.fee * 1.9 <= claimMain3.fee && claimMain3.fee <= claimMain2.fee * 2.1)
  }

}
