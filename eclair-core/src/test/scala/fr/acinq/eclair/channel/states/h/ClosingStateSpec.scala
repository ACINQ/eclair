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

import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{ConfirmationPriority, ConfirmationTarget, FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT}
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshiLong, TestConstants, TestKitBaseClass, TimestampSecond, randomBytes32, randomKey}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class ClosingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, alice2relayer: TestProbe, bob2relayer: TestProbe, channelUpdateListener: TestProbe, txListener: TestProbe, eventListener: TestProbe, bobCommitTxs: List[CommitTxAndRemoteSig])

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
        val channelConfig = ChannelConfig.standard
        val channelFlags = ChannelFlags(announceChannel = false)
        val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
        val aliceInit = Init(aliceParams.initFeatures)
        val bobInit = Init(bobParams.initFeatures)
        alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, fundingTxFeeBudget_opt = None, Some(TestConstants.initiatorPushAmount), requireConfirmedInputs = false, requestFunding_opt = None, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType, replyTo = aliceOpenReplyTo.ref.toTyped)
        alice2blockchain.expectMsgType[SetChannelId]
        bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, requireConfirmedInputs = false, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
        alice.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        alice.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        alice.underlying.system.eventStream.subscribe(eventListener.ref, classOf[ChannelAborted])
        bob.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        bob.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        bob.underlying.system.eventStream.subscribe(eventListener.ref, classOf[ChannelAborted])
        withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, alice2relayer, bob2relayer, channelUpdateListener, txListener, eventListener, Nil)))
      }
    } else {
      within(30 seconds) {
        reachNormal(setup, test.tags)
        alice.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        alice.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        bob.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])
        bob.underlying.system.eventStream.subscribe(txListener.ref, classOf[TransactionConfirmed])
        val bobCommitTxs: List[CommitTxAndRemoteSig] = (for (amt <- List(100000000 msat, 200000000 msat, 300000000 msat)) yield {
          val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
          crossSign(alice, bob, alice2bob, bob2alice)
          bob2relayer.expectMsgType[RelayForward]
          val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig
          fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
          // alice forwards the fulfill upstream
          alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fulfill]]
          crossSign(bob, alice, bob2alice, alice2bob)
          // bob confirms that it has forwarded the fulfill to alice
          awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(htlc.channelId).isEmpty)
          val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig
          bobCommitTx1 :: bobCommitTx2 :: Nil
        }).flatten

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
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[PublishTx] // claim-main-delayed
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
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[PublishTx] // claim-main-delayed
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
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[PublishTx] // claim-main-delayed
    alice2blockchain.expectMsgType[WatchTxConfirmed] // commitment
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim-main-delayed
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), TimestampSecond.now())
    alice2bob.expectNoMessage(200 millis)
    alice2blockchain.expectNoMessage(200 millis)
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (funder, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[PublishTx] // claim-main-delayed
    alice2blockchain.expectMsgType[WatchTxConfirmed] // commitment
    alice2blockchain.expectMsgType[WatchTxConfirmed] // claim-main-delayed
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    alice ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    alice2bob.expectNoMessage(200 millis)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == fundingTx) // we republish the funding tx
    assert(alice.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[PublishTx] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchTxConfirmed] // claim-main-delayed
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, Some(fundingTx), TimestampSecond.now())
    bob2alice.expectNoMessage(200 millis)
    bob2blockchain.expectNoMessage(200 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[PublishTx] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchTxConfirmed] // claim-main-delayed
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    bob2alice.expectNoMessage(200 millis)
    bob2blockchain.expectNoMessage(200 millis)
    assert(bob.stateName == CLOSING) // the above expectNoMsg will make us wait, so this checks that we are still in CLOSING
  }

  test("recv GetTxResponse (fundee, tx not found, timeout)", Tag("funding_unconfirmed")) { f =>
    import f._
    val sender = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
    bob ! CMD_FORCECLOSE(sender.ref)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[PublishTx] // claim-main-delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed] // commitment
    bob2blockchain.expectMsgType[WatchTxConfirmed] // claim-main-delayed
    eventListener.expectMsgType[ChannelAborted]

    // test starts here
    bob.setState(stateData = bob.stateData.asInstanceOf[DATA_CLOSING].copy(waitingSince = bob.underlyingActor.nodeParams.currentBlockHeight - Channel.FUNDING_TIMEOUT_FUNDEE - 1))
    bob ! GetTxWithMetaResponse(fundingTx.txid, None, TimestampSecond.now())
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(200 millis)
    assert(bob.stateName == CLOSED)
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, ByteVector32(ByteVector.fill(32)(1)), cltvExpiry = CltvExpiry(300000), onion = TestConstants.emptyOnionPacket, None, 1.0, None, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_FULFILL_HTLC (unexisting htlc)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

    // actual test starts here
    val sender = TestProbe()
    val c = CMD_FULFILL_HTLC(42, randomBytes32(), replyTo_opt = Some(sender.ref))
    alice ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(alice), 42)))

    // NB: nominal case is tested in IntegrationSpec
  }

  test("mutual close (taproot channel)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last
    assert(Script.isPay2tr(Script.parse(mutualCloseTx.input.txOut.publicKeyScript)))
    
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, mutualCloseTx.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  def testMutualCloseBeforeConverge(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._
    val sender = TestProbe()
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)
    bob.underlyingActor.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(2500 sat)).copy(minimum = FeeratePerKw(250 sat), slow = FeeratePerKw(250 sat)))
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
    testMutualCloseBeforeConverge(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchFundingSpentTriggered (mutual close before converging, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testMutualCloseBeforeConverge(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchTxConfirmedTriggered (mutual close)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last

    // actual test starts here
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
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // actual test starts here
    // we are notified afterwards from our watcher about the tx that we just published
    alice ! WatchFundingSpentTriggered(aliceCommitTx)
    assert(alice.stateData == initialState) // this was a no-op
  }

  test("recv WatchFundingSpentTriggered (local commit, public channel)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._

    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400_000), 42, null)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400_000), 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    assert(listener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)

    // an error occurs and alice publishes her commit tx
    localClose(alice, alice2blockchain)
    // she notifies the network that the channel shouldn't be used anymore
    assert(!listener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
  }

  test("recv WatchOutputSpentTriggered") { f =>
    import f._
    // alice sends an htlc to bob
    val (ra1, htlca1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob2relayer.expectMsgType[RelayForward]
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]

    // scenario 1: bob claims the htlc output from the commit tx using its preimage
    val claimHtlcSuccessFromCommitTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomTxId(), 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessClaimHtlcSuccessFromCommitTx(Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33))) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchOutputSpentTriggered(claimHtlcSuccessFromCommitTx)
    val fulfill1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill1.htlc == htlca1)
    assert(fulfill1.result.paymentPreimage == ra1)

    // scenario 2: bob claims the htlc output from his own commit tx using its preimage (let's assume both parties had published their commitment tx)
    val claimHtlcSuccessTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomTxId(), 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessHtlcSuccess(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33), Transactions.DefaultCommitmentFormat)) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchOutputSpentTriggered(claimHtlcSuccessTx)
    val fulfill2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill2.htlc == htlca1)
    assert(fulfill2.result.paymentPreimage == ra1)

    assert(alice.stateData == initialState) // this was a no-op
  }

  test("recv WatchOutputSpentTriggered (simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // alice sends an htlc to bob
    val (ra1, htlca1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob2relayer.expectMsgType[RelayForward]
    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.isDefined)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]

    // scenario 1: bob claims the htlc output from the commit tx using its preimage
    val claimHtlcSuccessFromCommitTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomTxId(), 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessClaimHtlcSuccessFromCommitTx(Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33))) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchOutputSpentTriggered(claimHtlcSuccessFromCommitTx)
    val fulfill1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill1.htlc == htlca1)
    assert(fulfill1.result.paymentPreimage == ra1)

    // scenario 2: bob claims the htlc output from his own commit tx using its preimage (let's assume both parties had published their commitment tx)
    val claimHtlcSuccessTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint(randomTxId(), 0), signatureScript = ByteVector.empty, sequence = 0, witness = Scripts.witnessHtlcSuccess(Transactions.PlaceHolderSig, Transactions.PlaceHolderSig, ra1, ByteVector.fill(130)(33), Transactions.DefaultCommitmentFormat)) :: Nil, txOut = Nil, lockTime = 0)
    alice ! WatchOutputSpentTriggered(claimHtlcSuccessTx)
    val fulfill2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFulfill]]
    assert(fulfill2.htlc == htlca1)
    assert(fulfill2.result.paymentPreimage == ra1)

    assert(alice.stateData == initialState) // this was a no-op
  }
  
  test("recv CMD_BUMP_FORCE_CLOSE_FEE (local commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    localClose(alice, alice2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.localCommitPublished.nonEmpty)
    val localCommitPublished1 = initialState.localCommitPublished.get
    assert(localCommitPublished1.claimAnchorTxs.nonEmpty)
    val Some(localAnchor1) = localCommitPublished1.claimAnchorTxs.collectFirst { case tx: ClaimLocalAnchorOutputTx => tx }
    assert(localAnchor1.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Medium))

    val replyTo = TestProbe()
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    val localAnchor2 = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimLocalAnchorOutputTx]
    assert(localAnchor2.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    val localCommitPublished2 = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished2.claimAnchorTxs.contains(localAnchor2))

    // If we try bumping again, but with a lower priority, this won't override the previous priority.
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Medium))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    val localAnchor3 = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimLocalAnchorOutputTx]
    assert(localAnchor3.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.contains(localCommitPublished2))
  }

  def testLocalCommitTxConfirmed(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)

    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[LocalCommitConfirmed])
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])

    // alice sends an htlc to bob
    val (_, htlca1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends an htlc below dust to bob
    val amountBelowDust = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.dustLimit - 100.msat
    val (_, htlca2) = addHtlc(amountBelowDust, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val closingState = localClose(alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainDelayedOutputTx.isDefined)
    assert(closingState.htlcTxs.size == 1)
    assert(getHtlcSuccessTxs(closingState).isEmpty)
    assert(getHtlcTimeoutTxs(closingState).length == 1)
    val htlcTimeoutTx = getHtlcTimeoutTxs(closingState).head.tx
    assert(closingState.claimHtlcDelayedTxs.length == 0)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingState.commitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == closingState.commitTx)
    assert(listener.expectMsgType[LocalCommitConfirmed].refundAtBlock == BlockHeight(42) + bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.toSelfDelay.toInt)
    assert(listener.expectMsgType[PaymentSettlingOnChain].paymentHash == htlca1.paymentHash)
    // htlcs below dust will never reach the chain, once the commit tx is confirmed we can consider them failed
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlca2)
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(200), 0, closingState.claimMainDelayedOutputTx.get.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcTimeoutTx)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.irrevocablySpent.values.toSet == Set(closingState.commitTx, closingState.claimMainDelayedOutputTx.get.tx, htlcTimeoutTx))
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlca1)
    alice2relayer.expectNoMessage(100 millis)

    // We claim the htlc-delayed output now that the HTLC tx has been confirmed.
    val claimHtlcDelayedTx = alice2blockchain.expectMsgType[PublishFinalTx]
    Transaction.correctlySpends(claimHtlcDelayedTx.tx, Seq(htlcTimeoutTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.length == 1)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcDelayedTx.tx)

    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit)") { f =>
    testLocalCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchTxConfirmedTriggered (local commit, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testLocalCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchTxConfirmedTriggered (local commit, simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testLocalCommitTxConfirmed(f, ChannelFeatures(Features.SimpleTaprootStaging))
  }

  test("recv WatchTxConfirmedTriggered (local commit with multiple htlcs for the same payment)") { f =>
    import f._

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(30000000 msat, alice, bob, alice2bob, bob2alice)
    // and more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(25000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(30000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    val amountBelowDust = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.dustLimit - 100.msat
    val (_, dustCmd) = makeCmdAdd(amountBelowDust, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val dust = addHtlc(dustCmd, alice, bob, alice2bob, bob2alice)
    val (_, cmd4) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight + 1, ra1)
    val htlca4 = addHtlc(cmd4, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val closingState = localClose(alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainDelayedOutputTx.isDefined)
    assert(closingState.htlcTxs.size == 4)
    assert(getHtlcSuccessTxs(closingState).isEmpty)
    val htlcTimeoutTxs = getHtlcTimeoutTxs(closingState).map(_.tx)
    assert(htlcTimeoutTxs.length == 4)
    assert(closingState.claimHtlcDelayedTxs.length == 0)

    // if commit tx and htlc-timeout txs end up in the same block, we may receive the htlc-timeout confirmation before the commit tx confirmation
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, htlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 1, closingState.commitTx)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == dust)
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(200), 0, closingState.claimMainDelayedOutputTx.get.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, htlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 1, htlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 0, htlcTimeoutTxs(3))
    val forwardedFail4 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3, forwardedFail4) == Set(htlca1, htlca2, htlca3, htlca4))
    alice2relayer.expectNoMessage(250 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.length == 4)
    val claimHtlcDelayedTxs = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 0, claimHtlcDelayedTxs(0).tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcDelayedTxs(1).tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 2, claimHtlcDelayedTxs(2).tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 3, claimHtlcDelayedTxs(3).tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit with htlcs only signed by local)") { f =>
    import f._
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
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
    assert(closingState.htlcTxs.isEmpty && closingState.claimHtlcDelayedTxs.isEmpty)
    // when the commit tx is confirmed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, aliceCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(2 seconds)
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
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 1)
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    // Note that alice has not signed the htlc yet!
    // We make her unilaterally close the channel.
    val closingState = localClose(alice, alice2blockchain)

    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcTxs.isEmpty && closingState.claimHtlcDelayedTxs.isEmpty)
    // Alice should ignore the htlc (she hasn't relayed it yet): it is Bob's responsibility to claim it.
    // Once the commit tx and her main output are confirmed, she can consider the channel closed.
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, aliceCommitTx)
    closingState.claimMainDelayedOutputTx.foreach(claimMain => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimMain.tx))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (local commit with fulfill only signed by local)") { f =>
    import f._
    // bob sends an htlc
    val (r, htlc) = addHtlc(110000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[RelayForward]
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(aliceCommitTx.txOut.size == 3) // 2 main outputs + 1 htlc

    // alice fulfills the HTLC but bob doesn't receive the signature
    alice ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    alice2bob.expectMsgType[UpdateFulfillHtlc]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    // note that bob doesn't receive the new sig!
    // then we make alice unilaterally close the channel
    val closingState = localClose(alice, alice2blockchain)
    assert(closingState.commitTx.txid == aliceCommitTx.txid)
    assert(getHtlcTimeoutTxs(closingState).isEmpty)
    assert(getHtlcSuccessTxs(closingState).length == 1)
  }

  test("recv WatchTxConfirmedTriggered (local commit with fail not acked by remote)") { f =>
    import f._
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val (_, htlc) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
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
    val closingState = localClose(alice, alice2blockchain)
    assert(closingState.commitTx.txOut.length == 2) // htlc has been removed

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.htlcTxs.isEmpty && closingState.claimHtlcDelayedTxs.isEmpty)
    // when the commit tx is confirmed, alice knows that the htlc will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.commitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(2 seconds)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv INPUT_RESTORED (local commit)") { f =>
    import f._

    // alice sends an htlc to bob
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val closingState = localClose(alice, alice2blockchain)
    val htlcTimeoutTx = getHtlcTimeoutTxs(closingState).head

    // simulate a node restart after a feerate increase
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice.underlyingActor.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(15_000 sat)))
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == closingState.commitTx)
    closingState.claimMainDelayedOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMain.tx))
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == htlcTimeoutTx.tx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == closingState.commitTx.txid)
    closingState.claimMainDelayedOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid))
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == htlcTimeoutTx.input.outPoint.index)

    // the htlc transaction confirms, so we publish a 3rd-stage transaction
    alice ! WatchTxConfirmedTriggered(BlockHeight(2701), 1, closingState.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(2702), 0, htlcTimeoutTx.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.nonEmpty)
    val beforeSecondRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    val claimHtlcTimeoutTx = beforeSecondRestart.localCommitPublished.get.claimHtlcDelayedTxs.head
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimHtlcTimeoutTx.tx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcTimeoutTx.tx.txid)

    // simulate another node restart
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeSecondRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // we should re-publish unconfirmed transactions
    closingState.claimMainDelayedOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMain.tx))
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimHtlcTimeoutTx.tx)
    closingState.claimMainDelayedOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid))
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcTimeoutTx.tx.txid)
  }

  test("recv INPUT_RESTORED (local commit with htlc-delayed transactions)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    // Alice has one incoming and one outgoing HTLC.
    addHtlc(75_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (preimage, incomingHtlc) = addHtlc(80_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Alice force-closes.
    val closingState1 = localClose(alice, alice2blockchain)
    assert(closingState1.claimMainDelayedOutputTx.nonEmpty)
    val claimMainTx = closingState1.claimMainDelayedOutputTx.get.tx
    assert(getHtlcSuccessTxs(closingState1).isEmpty)
    assert(getHtlcTimeoutTxs(closingState1).length == 1)
    val htlcTimeoutTx = getHtlcTimeoutTxs(closingState1).head.tx

    // The commit tx confirms.
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingState1.commitTx)
    alice2blockchain.expectNoMessage(100 millis)

    // Alice receives the preimage for the incoming HTLC.
    alice ! CMD_FULFILL_HTLC(incomingHtlc.id, preimage, commit = true)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == claimMainTx.txid)
    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[HtlcTimeoutTx])
    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[HtlcSuccessTx])
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainTx.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    alice2blockchain.expectMsgType[WatchOutputSpent]
    alice2blockchain.expectNoMessage(100 millis)
    val closingState2 = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(getHtlcSuccessTxs(closingState2).length == 1)
    val htlcSuccessTx = getHtlcSuccessTxs(closingState2).head.tx

    // The HTLC txs confirms, so we publish 3rd-stage txs.
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcTimeoutTx)
    val claimHtlcTimeoutDelayedTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    inside(alice2blockchain.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == claimHtlcTimeoutDelayedTx.txid)
      assert(w.delay_opt.map(_.parentTxId).contains(htlcTimeoutTx.txid))
    }
    Transaction.correctlySpends(claimHtlcTimeoutDelayedTx, Seq(htlcTimeoutTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, htlcSuccessTx)
    val claimHtlcSuccessDelayedTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    inside(alice2blockchain.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == claimHtlcSuccessDelayedTx.txid)
      assert(w.delay_opt.map(_.parentTxId).contains(htlcSuccessTx.txid))
    }
    Transaction.correctlySpends(claimHtlcSuccessDelayedTx, Seq(htlcSuccessTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // We simulate a node restart after a feerate increase.
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.underlyingActor.nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(FeeratePerKw(15_000 sat)))
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // We re-publish closing transactions.
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == claimMainTx.txid)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == claimHtlcTimeoutDelayedTx.txid)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == claimHtlcSuccessDelayedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcTimeoutDelayedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcSuccessDelayedTx.txid)

    // We replay the HTLC fulfillment: nothing happens since we already published a 3rd-stage transaction.
    alice ! CMD_FULFILL_HTLC(incomingHtlc.id, preimage, commit = true)
    alice2blockchain.expectNoMessage(100 millis)

    // The remaining transactions confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 0, claimMainTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 1, claimHtlcTimeoutDelayedTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(43), 2, claimHtlcSuccessDelayedTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with htlcs only signed by local in next remote commit)") { f =>
    import f._
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[PaymentSettlingOnChain])
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    // alice sends an htlc
    val (_, htlc) = addHtlc(4200000 msat, alice, bob, alice2bob, bob2alice)
    // and signs it (but bob doesn't sign it)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    channelUpdateListener.expectMsgType[LocalChannelDown]
    assert(closingState.claimMainOutputTx.isEmpty)
    assert(closingState.claimHtlcTxs.isEmpty)
    // when the commit tx is signed, alice knows that the htlc she sent right before the unilateral close will never reach the chain
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // so she fails it
    val origin = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.originChannels(htlc.id)
    alice2relayer.expectMsg(RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId(alice), htlc))))
    // the htlc will not settle on chain
    listener.expectNoMessage(2 seconds)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit with settled htlcs)") { f =>
    import f._

    // alice sends two htlcs to bob
    val (preimage1, htlc1) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (_, htlc2) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // bob fulfills one HTLC and fails the other one without revoking its previous commitment.
    fulfillHtlc(htlc1.id, preimage1, bob, alice, bob2alice, alice2bob)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFulfill]].htlc == htlc1)
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
    val bobCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit
    assert(bobCommit.htlcTxsAndRemoteSigs.isEmpty)
    val commitTx = bobCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, commitTx)
    // alice propagates the HTLC failure upstream.
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc2)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(bobCommitTx.txOut.exists(_.publicKeyScript == Script.write(Script.pay2wpkh(DummyOnChainWallet.dummyReceivePubkey)))) // bob's commit tx sends directly to our wallet
    assert(closingState.claimMainOutputTx.isEmpty)
    assert(closingState.claimHtlcTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
  }

  test("recv WatchFundingSpentTriggered (remote commit) taproot channel ", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 4) // two main outputs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcTxs.isEmpty)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
  }

  test("recv WatchFundingSpentTriggered (remote commit, public channel)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._

    val listener = TestProbe()
    systemA.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400_000), 42, null)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400_000), 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    assert(listener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)

    // bob publishes his commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    remoteClose(bobCommitTx, alice, alice2blockchain)
    // alice notifies the network that the channel shouldn't be used anymore
    assert(!listener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
  }

  test("recv WatchFundingSpentTriggered (remote commit, option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    // Bob publishes his last current commit tx, the one it had when entering NEGOTIATING state.
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimHtlcTxs.isEmpty)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
  }

  test("recv CMD_BUMP_FORCE_CLOSE_FEE (remote commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    val closingState1 = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState1.claimAnchorTxs.nonEmpty)
    val Some(localAnchor1) = closingState1.claimAnchorTxs.collectFirst { case tx: ClaimLocalAnchorOutputTx => tx }
    assert(localAnchor1.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Medium))

    val replyTo = TestProbe()
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    val localAnchor2 = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimLocalAnchorOutputTx]
    assert(localAnchor2.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    val closingState2 = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    assert(closingState2.claimAnchorTxs.contains(localAnchor2))

    // If we try bumping again, but with a lower priority, this won't override the previous priority.
    alice ! CMD_BUMP_FORCE_CLOSE_FEE(replyTo.ref, ConfirmationTarget.Priority(ConfirmationPriority.Medium))
    replyTo.expectMsgType[RES_SUCCESS[CMD_BUMP_FORCE_CLOSE_FEE]]
    val localAnchor3 = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimLocalAnchorOutputTx]
    assert(localAnchor3.confirmationTarget == ConfirmationTarget.Priority(ConfirmationPriority.Fast))
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.contains(closingState2))
  }

  test("recv WatchTxConfirmedTriggered (remote commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 4) // two main outputs + two anchors
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    txListener.expectMsgType[TransactionPublished]
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.claimMainOutputTx.get.tx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    assert(alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].commitments.params.channelFeatures == ChannelFeatures(Features.StaticRemoteKey))
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 2) // two main outputs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice won't create a claimMainOutputTx because her main output is already spendable by the wallet
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimMainOutputTx.isEmpty)
    assert(alice.stateName == CLOSING)
    // once the remote commit is confirmed the channel is definitively closed
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(initialState.commitments.params.channelFeatures == ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
    // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
    val bobCommitTx = bobCommitTxs.last.commitTx.tx
    assert(bobCommitTx.txOut.size == 4) // two main outputs + two anchors
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)

    // actual test starts here
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcTxs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.claimMainOutputTx.get.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  def testRemoteCommitTxWithHtlcsConfirmed(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)

    // alice sends a first htlc to bob
    val (ra1, htlca1) = addHtlc(15000000 msat, alice, bob, alice2bob, bob2alice)
    // alice sends more htlcs with the same payment_hash
    val (_, cmd2) = makeCmdAdd(15000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight, ra1)
    val htlca2 = addHtlc(cmd2, alice, bob, alice2bob, bob2alice)
    val (_, cmd3) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, alice.underlyingActor.nodeParams.currentBlockHeight - 1, ra1)
    val htlca3 = addHtlc(cmd3, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob publishes the latest commit tx.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(bobCommitTx.txOut.length == 7) // two main outputs + two anchors + 3 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 5) // two main outputs + 3 HTLCs
    }
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimHtlcTxs.size == 3)
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState).map(_.tx)
    assert(claimHtlcTimeoutTxs.length == 3)

    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    // for static_remote_key channels there is no claimMainOutputTx (bob's commit tx directly sends to our wallet)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, claimMainOutputTx.tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, claimHtlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == Set(htlca1, htlca2, htlca3))
    alice2relayer.expectNoMessage(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment)") { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
  }

  test("recv WatchTxConfirmedTriggered (remote commit with multiple htlcs for the same payment, simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRemoteCommitTxWithHtlcsConfirmed(f, ChannelFeatures(Features.SimpleTaprootStaging))
  }

  test("recv WatchTxConfirmedTriggered (remote commit) followed by CMD_FULFILL_HTLC") { f =>
    import f._
    // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
    val (r1, htlc1) = addHtlc(110000000 msat, CltvExpiryDelta(48), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[RelayForward]

    // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
    val (_, htlc2) = addHtlc(95000000 msat, CltvExpiryDelta(144), alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig] // We stop here: Alice sent her CommitSig, but doesn't hear back from Bob.

    // Now Bob publishes the first commit tx (force-close).
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.length == 3) // two main outputs + 1 HTLC
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.isEmpty)
    assert(bobCommitTx.txOut.exists(_.publicKeyScript == Script.write(Script.pay2wpkh(DummyOnChainWallet.dummyReceivePubkey))))
    assert(closingState.claimHtlcTxs.size == 1)
    assert(getClaimHtlcSuccessTxs(closingState).isEmpty) // we don't have the preimage to claim the htlc-success yet
    assert(getClaimHtlcTimeoutTxs(closingState).isEmpty)

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)
    val claimHtlcSuccessTx = getClaimHtlcSuccessTxs(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get).head.tx
    Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val publishHtlcSuccessTx = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimHtlcSuccessTx]
    assert(publishHtlcSuccessTx.tx == claimHtlcSuccessTx)
    assert(publishHtlcSuccessTx.confirmationTarget == ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))

    // Alice resets watches on all relevant transactions.
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    val watchHtlcSuccess = alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(watchHtlcSuccess.txId == bobCommitTx.txid)
    assert(watchHtlcSuccess.outputIndex == claimHtlcSuccessTx.txIn.head.outPoint.index)
    alice2blockchain.expectNoMessage(100 millis)

    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // The second htlc was not included in the commit tx published on-chain, so we can consider it failed
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimHtlcSuccessTx)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.irrevocablySpent.values.toSet == Set(bobCommitTx, claimHtlcSuccessTx))
    awaitCond(alice.stateName == CLOSED)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv WatchTxConfirmedTriggered (remote commit, simple taproot channels) followed by CMD_FULFILL_HTLC", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
    val (r1, htlc1) = addHtlc(110000000 msat, CltvExpiryDelta(48), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[RelayForward]

    // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
    val (_, htlc2) = addHtlc(95000000 msat, CltvExpiryDelta(144), alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig] // We stop here: Alice sent her CommitSig, but doesn't hear back from Bob.

    // Now Bob publishes the first commit tx (force-close).
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.length == 5) // two main outputs + two anchor outputs + 1 HTLC
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(closingState.claimMainOutputTx.nonEmpty)
    assert(closingState.claimHtlcTxs.size == 1)
    assert(getClaimHtlcSuccessTxs(closingState).isEmpty) // we don't have the preimage to claim the htlc-success yet
    assert(getClaimHtlcTimeoutTxs(closingState).isEmpty)

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)
    alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == closingState.claimMainOutputTx.get.tx)
    val claimHtlcSuccessTx = getClaimHtlcSuccessTxs(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get).head.tx
    Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val publishHtlcSuccessTx = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimHtlcSuccessTx]
    assert(publishHtlcSuccessTx.tx == claimHtlcSuccessTx)
    assert(publishHtlcSuccessTx.confirmationTarget == ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))

    // Alice resets watches on all relevant transactions.
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == closingState.claimMainOutputTx.get.tx.txid)
    val watchHtlcSuccess = alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(watchHtlcSuccess.txId == bobCommitTx.txid)
    assert(watchHtlcSuccess.outputIndex == claimHtlcSuccessTx.txIn.head.outPoint.index)
    alice2blockchain.expectNoMessage(100 millis)

    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // The second htlc was not included in the commit tx published on-chain, so we can consider it failed
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, closingState.claimMainOutputTx.get.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimHtlcSuccessTx)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.irrevocablySpent.values.toSet == Set(bobCommitTx, closingState.claimMainOutputTx.get.tx, claimHtlcSuccessTx))
    awaitCond(alice.stateName == CLOSED)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv INPUT_RESTORED (remote commit)") { f =>
    import f._

    // alice sends an htlc to bob
    val (_, htlca) = addHtlc(50000000 msat, CltvExpiryDelta(24), alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    val htlcTimeoutTx = getClaimHtlcTimeoutTxs(closingState).head
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // we should re-publish unconfirmed transactions
    closingState.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMain.tx))
    val publishClaimHtlcTimeoutTx = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimHtlcTimeoutTx]
    assert(publishClaimHtlcTimeoutTx.tx == htlcTimeoutTx.tx)
    assert(publishClaimHtlcTimeoutTx.confirmationTarget == ConfirmationTarget.Absolute(htlca.cltvExpiry.blockHeight))
    closingState.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid))
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == htlcTimeoutTx.input.outPoint.index)
  }

  private def testNextRemoteCommitTxConfirmed(f: FixtureParam, channelFeatures: ChannelFeatures): (Transaction, RemoteCommitPublished, Set[UpdateAddHtlc]) = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)

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
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(bobCommitTx.txOut.length == 7) // two main outputs + two anchors + 3 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 5) // two main outputs + 3 HTLCs
    }
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    assert(getClaimHtlcTimeoutTxs(closingState).length == 3)
    (bobCommitTx, closingState, Set(htlca1, htlca2, htlca3))
  }

  test("recv WatchTxConfirmedTriggered (next remote commit)") { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState).map(_.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, claimMainOutputTx.tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, claimHtlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState).map(_.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    assert(closingState.claimMainOutputTx.isEmpty) // with static_remotekey we don't claim out main output
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, claimHtlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState).map(_.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, claimMainOutputTx.tx))
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, claimHtlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit, simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val (bobCommitTx, closingState, htlcs) = testNextRemoteCommitTxConfirmed(f, ChannelFeatures(Features.SimpleTaprootStaging))
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState).map(_.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(45), 0, closingState.claimMainOutputTx.get.tx)
    alice2relayer.expectNoMessage(100 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(201), 0, claimHtlcTimeoutTxs(0))
    val forwardedFail1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(202), 0, claimHtlcTimeoutTxs(1))
    val forwardedFail2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    alice2relayer.expectNoMessage(250 millis)
    alice ! WatchTxConfirmedTriggered(BlockHeight(203), 1, claimHtlcTimeoutTxs(2))
    val forwardedFail3 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc
    assert(Set(forwardedFail1, forwardedFail2, forwardedFail3) == htlcs)
    alice2relayer.expectNoMessage(250 millis)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (next remote commit) followed by CMD_FULFILL_HTLC") { f =>
    import f._
    // An HTLC Bob -> Alice is cross-signed that will be fulfilled later.
    val (r1, htlc1) = addHtlc(110000000 msat, CltvExpiryDelta(64), bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[RelayForward]

    // An HTLC Alice -> Bob is only signed by Alice: Bob has two spendable commit tx.
    val (_, htlc2) = addHtlc(95000000 msat, CltvExpiryDelta(32), alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck] // not forwarded to Alice (malicious Bob)
    bob2alice.expectMsgType[CommitSig] // not forwarded to Alice (malicious Bob)

    // Now Bob publishes the next commit tx (force-close).
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.length == 4) // two main outputs + 2 HTLCs
    val closingState = remoteClose(bobCommitTx, alice, alice2blockchain)
    if (!bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures.paysDirectlyToWallet) {
      assert(closingState.claimMainOutputTx.nonEmpty)
    } else {
      assert(closingState.claimMainOutputTx.isEmpty)
    }
    assert(closingState.claimHtlcTxs.size == 2)
    assert(getClaimHtlcSuccessTxs(closingState).isEmpty) // we don't have the preimage to claim the htlc-success yet
    assert(getClaimHtlcTimeoutTxs(closingState).length == 1)
    val claimHtlcTimeoutTx = getClaimHtlcTimeoutTxs(closingState).head.tx

    // Alice receives the preimage for the first HTLC from downstream; she can now claim the corresponding HTLC output.
    alice ! CMD_FULFILL_HTLC(htlc1.id, r1, commit = true)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMainOutputTx.tx))
    val claimHtlcSuccessTx = getClaimHtlcSuccessTxs(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get).head.tx
    Transaction.correctlySpends(claimHtlcSuccessTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val publishHtlcSuccessTx = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimHtlcSuccessTx]
    assert(publishHtlcSuccessTx.tx == claimHtlcSuccessTx)
    assert(publishHtlcSuccessTx.confirmationTarget == ConfirmationTarget.Absolute(htlc1.cltvExpiry.blockHeight))
    val publishHtlcTimeoutTx = alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.asInstanceOf[ClaimHtlcTimeoutTx]
    assert(publishHtlcTimeoutTx.tx == claimHtlcTimeoutTx)
    assert(publishHtlcTimeoutTx.confirmationTarget == ConfirmationTarget.Absolute(htlc2.cltvExpiry.blockHeight))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainOutputTx.tx.txid))
    val watchHtlcs = alice2blockchain.expectMsgType[WatchOutputSpent] :: alice2blockchain.expectMsgType[WatchOutputSpent] :: Nil
    watchHtlcs.foreach(ws => assert(ws.txId == bobCommitTx.txid))
    assert(watchHtlcs.map(_.outputIndex).toSet == Set(claimHtlcSuccessTx, claimHtlcTimeoutTx).map(_.txIn.head.outPoint.index))
    alice2blockchain.expectNoMessage(100 millis)

    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    closingState.claimMainOutputTx.foreach(claimMainOutputTx => alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimMainOutputTx.tx))
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimHtlcSuccessTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimHtlcTimeoutTx)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc == htlc2)
    awaitCond(alice.stateName == CLOSED)
    alice2blockchain.expectNoMessage(100 millis)
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv INPUT_RESTORED (next remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val (bobCommitTx, closingState, _) = testNextRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(closingState)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    closingState.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMain.tx))
    claimHtlcTimeoutTxs.foreach(claimHtlcTimeout => assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.tx == claimHtlcTimeout.tx))
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    closingState.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid))
    claimHtlcTimeoutTxs.foreach(claimHtlcTimeout => assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == claimHtlcTimeout.input.outPoint.index))
  }

  private def testFutureRemoteCommitTxConfirmed(f: FixtureParam, channelFeatures: ChannelFeatures): Transaction = {
    import f._
    val oldStateData = alice.stateData
    assert(oldStateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)
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
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(bobCommitTx.txOut.length == 6) // two main outputs + two anchors + 2 HTLCs
      case DefaultCommitmentFormat => assert(bobCommitTx.txOut.length == 4) // two main outputs + 2 HTLCs
    }
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    bobCommitTx
  }

  test("recv WatchTxConfirmedTriggered (future remote commit)") { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobCommitTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the remote commit
    // bob's commit tx sends directly to alice's wallet
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)
    alice2blockchain.expectNoMessage(250 millis) // alice ignores the htlc-timeout

    // actual test starts here
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobCommitTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchTxConfirmedTriggered (future remote commit, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
    // using option_static_remotekey alice doesn't need to sweep her output
    awaitCond(alice.stateName == CLOSING, 10 seconds)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    // after the commit tx is confirmed the channel is closed, no claim transactions needed
    awaitCond(alice.stateName == CLOSED, 10 seconds)
  }

  test("recv WatchTxConfirmedTriggered (future remote commit, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
    // alice is able to claim its main output
    val claimMainTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimMainTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainTx.txid)
    alice2blockchain.expectNoMessage(250 millis) // alice ignores the htlc-timeout

    // actual test starts here
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, bobCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(0), 0, claimMainTx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_RESTORED (future remote commit)") { f =>
    import f._

    val bobCommitTx = testFutureRemoteCommitTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    awaitCond(alice.stateName == CLOSING)

    // bob's commit tx sends funds directly to our wallet
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
  }

  case class RevokedCloseFixture(bobRevokedTxs: Seq[LocalCommit], htlcsAlice: Seq[(UpdateAddHtlc, ByteVector32)], htlcsBob: Seq[(UpdateAddHtlc, ByteVector32)])

  private def prepareRevokedClose(f: FixtureParam, channelFeatures: ChannelFeatures): RevokedCloseFixture = {
    import f._

    // Bob's first commit tx doesn't contain any htlc
    val localCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(localCommit1.commitTxAndRemoteSig.commitTx.tx.txOut.size == 4) // 2 main outputs + 2 anchors
      case DefaultCommitmentFormat => assert(localCommit1.commitTxAndRemoteSig.commitTx.tx.txOut.size == 2) // 2 main outputs
    }

    // Bob's second commit tx contains 1 incoming htlc and 1 outgoing htlc
    val (localCommit2, htlcAlice1, htlcBob1) = {
      val (ra, htlcAlice) = addHtlc(35000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (rb, htlcBob) = addHtlc(20000000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val localCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit
      (localCommit, (htlcAlice, ra), (htlcBob, rb))
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.size == localCommit2.commitTxAndRemoteSig.commitTx.tx.txOut.size)
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(localCommit2.commitTxAndRemoteSig.commitTx.tx.txOut.size == 6)
      case DefaultCommitmentFormat => assert(localCommit2.commitTxAndRemoteSig.commitTx.tx.txOut.size == 4)
    }

    // Bob's third commit tx contains 2 incoming htlcs and 2 outgoing htlcs
    val (localCommit3, htlcAlice2, htlcBob2) = {
      val (ra, htlcAlice) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val (rb, htlcBob) = addHtlc(18000000 msat, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      val localCommit = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit
      (localCommit, (htlcAlice, ra), (htlcBob, rb))
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.size == localCommit3.commitTxAndRemoteSig.commitTx.tx.txOut.size)
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(localCommit3.commitTxAndRemoteSig.commitTx.tx.txOut.size == 8)
      case DefaultCommitmentFormat => assert(localCommit3.commitTxAndRemoteSig.commitTx.tx.txOut.size == 6)
    }

    // Bob's fourth commit tx doesn't contain any htlc
    val localCommit4 = {
      Seq(htlcAlice1, htlcAlice2).foreach { case (htlcAlice, _) => failHtlc(htlcAlice.id, bob, alice, bob2alice, alice2bob) }
      Seq(htlcBob1, htlcBob2).foreach { case (htlcBob, _) => failHtlc(htlcBob.id, alice, bob, alice2bob, bob2alice) }
      crossSign(alice, bob, alice2bob, bob2alice)
      bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit
    }

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.size == localCommit4.commitTxAndRemoteSig.commitTx.tx.txOut.size)
    channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => assert(localCommit4.commitTxAndRemoteSig.commitTx.tx.txOut.size == 4)
      case DefaultCommitmentFormat => assert(localCommit4.commitTxAndRemoteSig.commitTx.tx.txOut.size == 2)
    }

    RevokedCloseFixture(Seq(localCommit1, localCommit2, localCommit3, localCommit4), Seq(htlcAlice1, htlcAlice2), Seq(htlcBob1, htlcBob2))
  }

  private def setupFundingSpentRevokedTx(f: FixtureParam, channelFeatures: ChannelFeatures): (Transaction, RevokedCommitPublished) = {
    import f._

    val revokedCloseFixture = prepareRevokedClose(f, channelFeatures)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)

    // bob publishes one of his revoked txs
    val bobRevokedTx = revokedCloseFixture.bobRevokedTxs(1).commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedTx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedTx)
    if (!channelFeatures.paysDirectlyToWallet) {
      assert(rvk.claimMainOutputTx.nonEmpty)
    }
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.size == 2)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)
    val penaltyTxs = rvk.claimMainOutputTx.toList ++ rvk.mainPenaltyTx.toList ++ rvk.htlcPenaltyTxs

    // alice publishes the penalty txs
    if (!channelFeatures.paysDirectlyToWallet) {
      assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == rvk.claimMainOutputTx.get.tx)
    }
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == rvk.mainPenaltyTx.get.tx)
    assert(Set(alice2blockchain.expectMsgType[PublishFinalTx].tx, alice2blockchain.expectMsgType[PublishFinalTx].tx) == rvk.htlcPenaltyTxs.map(_.tx).toSet)
    for (penaltyTx <- penaltyTxs) {
      Transaction.correctlySpends(penaltyTx.tx, bobRevokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    // alice spends all outpoints of the revoked tx, except her main output when it goes directly to our wallet
    val spentOutpoints = penaltyTxs.flatMap(_.tx.txIn.map(_.outPoint)).toSet
    assert(spentOutpoints.forall(_.txid == bobRevokedTx.txid))
    if (channelFeatures.commitmentFormat.isInstanceOf[AnchorOutputsCommitmentFormat]) {
      assert(spentOutpoints.size == bobRevokedTx.txOut.size - 2) // we don't claim the anchors
    }
    else if (channelFeatures.paysDirectlyToWallet) {
      assert(spentOutpoints.size == bobRevokedTx.txOut.size - 1) // we don't claim our main output, it directly goes to our wallet
    } else {
      assert(spentOutpoints.size == bobRevokedTx.txOut.size)
    }

    // alice watches confirmation for the outputs only her can claim
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobRevokedTx.txid)
    if (!channelFeatures.paysDirectlyToWallet) {
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == rvk.claimMainOutputTx.get.tx.txid)
    }

    // alice watches outputs that can be spent by both parties
    val watchedOutpoints = Seq(alice2blockchain.expectMsgType[WatchOutputSpent], alice2blockchain.expectMsgType[WatchOutputSpent], alice2blockchain.expectMsgType[WatchOutputSpent]).map(_.outputIndex).toSet
    assert(watchedOutpoints == (rvk.mainPenaltyTx.get :: rvk.htlcPenaltyTxs).map(_.input.outPoint.index).toSet)
    alice2blockchain.expectNoMessage(1 second)

    (bobRevokedTx, rvk)
  }

  private def testFundingSpentRevokedTx(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._

    val (bobRevokedTx, rvk) = setupFundingSpentRevokedTx(f, channelFeatures)
    val txPublished = txListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == bobRevokedTx)
    assert(txPublished.miningFee > 0.sat) // alice is funder, she pays the fee for the revoked commit

    // once all txs are confirmed, alice can move to the closed state
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, bobRevokedTx)
    assert(txListener.expectMsgType[TransactionConfirmed].tx == bobRevokedTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 1, rvk.mainPenaltyTx.get.tx)
    if (!channelFeatures.paysDirectlyToWallet) {
      alice ! WatchTxConfirmedTriggered(BlockHeight(110), 2, rvk.claimMainOutputTx.get.tx)
    }
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, rvk.htlcPenaltyTxs(0).tx)
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, rvk.htlcPenaltyTxs(1).tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    testFundingSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testFundingSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testFundingSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
  }

  test("recv WatchFundingSpentTriggered (one revoked tx, simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testFundingSpentRevokedTx(f, ChannelFeatures(Features.SimpleTaprootStaging))
  }

  test("recv WatchFundingSpentTriggered (multiple revoked tx)") { f =>
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, ChannelFeatures(Features.StaticRemoteKey))
    assert(revokedCloseFixture.bobRevokedTxs.map(_.commitTxAndRemoteSig.commitTx.tx.txid).toSet.size == revokedCloseFixture.bobRevokedTxs.size) // all commit txs are distinct

    def broadcastBobRevokedTx(revokedTx: Transaction, htlcCount: Int, revokedCount: Int): RevokedCommitPublished = {
      alice ! WatchFundingSpentTriggered(revokedTx)
      awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == revokedCount)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.last.commitTx == revokedTx)

      // alice publishes penalty txs
      val mainPenalty = alice2blockchain.expectMsgType[PublishFinalTx].tx
      val claimMain_opt = if (!alice.stateData.asInstanceOf[DATA_CLOSING].commitments.params.channelFeatures.paysDirectlyToWallet) Some(alice2blockchain.expectMsgType[PublishFinalTx].tx) else None
      val htlcPenaltyTxs = (1 to htlcCount).map(_ => alice2blockchain.expectMsgType[PublishFinalTx].tx)
      (mainPenalty +: (claimMain_opt.toList ++ htlcPenaltyTxs)).foreach(tx => Transaction.correctlySpends(tx, revokedTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

      // alice watches confirmation for the outputs only her can claim
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == revokedTx.txid)
      claimMain_opt.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.txid))

      // alice watches outputs that can be spent by both parties
      assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == mainPenalty.txIn.head.outPoint.index)
      val htlcOutpoints = (1 to htlcCount).map(_ => alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex).toSet
      assert(htlcOutpoints == htlcPenaltyTxs.flatMap(_.txIn.map(_.outPoint.index)).toSet)
      alice2blockchain.expectNoMessage(1 second)

      alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.last
    }

    // bob publishes a first revoked tx (no htlc in that commitment)
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs.head.commitTxAndRemoteSig.commitTx.tx, 0, 1)
    // bob publishes a second revoked tx
    val rvk2 = broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(1).commitTxAndRemoteSig.commitTx.tx, 2, 2)
    // bob publishes a third revoked tx
    broadcastBobRevokedTx(revokedCloseFixture.bobRevokedTxs(2).commitTxAndRemoteSig.commitTx.tx, 4, 3)

    // bob's second revoked tx confirms: once all penalty txs are confirmed, alice can move to the closed state
    // NB: if multiple txs confirm in the same block, we may receive the events in any order
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 1, rvk2.mainPenaltyTx.get.tx)
    rvk2.claimMainOutputTx.foreach(claimMainOutputTx => alice ! WatchTxConfirmedTriggered(BlockHeight(100), 2, claimMainOutputTx.tx))
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, rvk2.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, rvk2.htlcPenaltyTxs(0).tx)
    assert(alice.stateName == CLOSING)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, rvk2.htlcPenaltyTxs(1).tx)
    awaitCond(alice.stateName == CLOSED)
  }

  def testInputRestoredRevokedTx(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._

    val (bobRevokedTx, rvk) = setupFundingSpentRevokedTx(f, channelFeatures)

    // simulate a node restart
    val beforeRestart = alice.stateData.asInstanceOf[DATA_CLOSING]
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(beforeRestart)
    alice2blockchain.expectMsgType[SetChannelId]
    awaitCond(alice.stateName == CLOSING)

    // the commit tx hasn't been confirmed yet, so we watch the funding output first
    alice2blockchain.expectMsgType[WatchFundingSpent]
    // then we should re-publish unconfirmed transactions
    rvk.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimMain.tx))
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == rvk.mainPenaltyTx.get.tx)
    rvk.htlcPenaltyTxs.foreach(htlcPenalty => assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == htlcPenalty.tx))
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobRevokedTx.txid)
    rvk.claimMainOutputTx.foreach(claimMain => assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid))
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == rvk.mainPenaltyTx.get.input.outPoint.index)
    rvk.htlcPenaltyTxs.foreach(htlcPenalty => assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex == htlcPenalty.input.outPoint.index))
  }

  test("recv INPUT_RESTORED (one revoked tx)") { f =>
    testInputRestoredRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv INPUT_RESTORED (one revoked tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testInputRestoredRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv INPUT_RESTORED (one revoked tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testInputRestoredRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
  }

  def testOutputSpentRevokedTx(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._
    val revokedCloseFixture = prepareRevokedClose(f, channelFeatures)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)
    val commitmentFormat = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.commitmentFormat

    // bob publishes one of his revoked txs
    val bobRevokedCommit = revokedCloseFixture.bobRevokedTxs(2)
    alice ! WatchFundingSpentTriggered(bobRevokedCommit.commitTxAndRemoteSig.commitTx.tx)

    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedCommit.commitTxAndRemoteSig.commitTx.tx)
    if (channelFeatures.paysDirectlyToWallet) {
      assert(rvk.claimMainOutputTx.isEmpty)
    } else {
      assert(rvk.claimMainOutputTx.nonEmpty)
    }
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.size == 4)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)

    // alice publishes the penalty txs and watches outputs
    val claimTxsCount = if (channelFeatures.paysDirectlyToWallet) 5 else 6 // 2 main outputs and 4 htlcs
    (1 to claimTxsCount).foreach(_ => alice2blockchain.expectMsgType[PublishTx])
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == rvk.commitTx.txid)
    if (!channelFeatures.paysDirectlyToWallet) {
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == rvk.claimMainOutputTx.get.tx.txid)
    }
    (1 to 5).foreach(_ => alice2blockchain.expectMsgType[WatchOutputSpent]) // main output penalty and 4 htlc penalties
    alice2blockchain.expectNoMessage(1 second)

    // bob manages to claim 2 htlc outputs before alice can penalize him: 1 htlc-success and 1 htlc-timeout.
    val (fulfilledHtlc, preimage) = revokedCloseFixture.htlcsAlice.head
    val (failedHtlc, _) = revokedCloseFixture.htlcsBob.last
    val bobHtlcSuccessTx1 = bobRevokedCommit.htlcTxsAndRemoteSigs.collectFirst {
      case HtlcTxAndRemoteSig(txInfo: HtlcSuccessTx, _) if txInfo.htlcId == fulfilledHtlc.id =>
        assert(fulfilledHtlc.paymentHash == txInfo.paymentHash)
        Transactions.addSigs(txInfo, ByteVector64.Zeroes, ByteVector64.Zeroes, preimage, commitmentFormat)
    }.get
    val bobHtlcTimeoutTx = bobRevokedCommit.htlcTxsAndRemoteSigs.collectFirst {
      case HtlcTxAndRemoteSig(txInfo: HtlcTimeoutTx, _) if txInfo.htlcId == failedHtlc.id =>
        Transactions.addSigs(txInfo, ByteVector64.Zeroes, ByteVector64.Zeroes, commitmentFormat)
    }.get
    val bobOutpoints = Seq(bobHtlcSuccessTx1, bobHtlcTimeoutTx).map(_.input.outPoint).toSet
    assert(bobOutpoints.size == 2)

    // alice reacts by publishing penalty txs that spend bob's htlc transactions
    alice ! WatchOutputSpentTriggered(bobHtlcSuccessTx1.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 1)
    val claimHtlcSuccessPenalty1 = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.last
    Transaction.correctlySpends(claimHtlcSuccessPenalty1.tx, bobHtlcSuccessTx1.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobHtlcSuccessTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimHtlcSuccessPenalty1.tx)
    val watchSpent1 = alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(watchSpent1.txId == bobHtlcSuccessTx1.tx.txid)
    assert(watchSpent1.outputIndex == claimHtlcSuccessPenalty1.input.outPoint.index)
    alice2blockchain.expectNoMessage(1 second)

    alice ! WatchOutputSpentTriggered(bobHtlcTimeoutTx.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 2)
    val claimHtlcTimeoutPenalty = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.last
    Transaction.correctlySpends(claimHtlcTimeoutPenalty.tx, bobHtlcTimeoutTx.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobHtlcTimeoutTx.tx.txid)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimHtlcTimeoutPenalty.tx)
    val watchSpent2 = alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(watchSpent2.txId == bobHtlcTimeoutTx.tx.txid)
    assert(watchSpent2.outputIndex == claimHtlcTimeoutPenalty.input.outPoint.index)
    alice2blockchain.expectNoMessage(1 second)

    // bob RBFs his htlc-success with a different transaction
    val bobHtlcSuccessTx2 = bobHtlcSuccessTx1.tx.copy(txIn = TxIn(OutPoint(randomTxId(), 0), Nil, 0) +: bobHtlcSuccessTx1.tx.txIn)
    assert(bobHtlcSuccessTx2.txid !== bobHtlcSuccessTx1.tx.txid)
    alice ! WatchOutputSpentTriggered(bobHtlcSuccessTx2)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 3)
    val claimHtlcSuccessPenalty2 = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.last
    assert(claimHtlcSuccessPenalty1.tx.txid != claimHtlcSuccessPenalty2.tx.txid)
    Transaction.correctlySpends(claimHtlcSuccessPenalty2.tx, bobHtlcSuccessTx2 :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobHtlcSuccessTx2.txid)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx == claimHtlcSuccessPenalty2.tx)
    val watchSpent3 = alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(watchSpent3.txId == bobHtlcSuccessTx2.txid)
    assert(watchSpent3.outputIndex == claimHtlcSuccessPenalty2.input.outPoint.index)
    alice2blockchain.expectNoMessage(1 second)

    // transactions confirm: alice can move to the closed state
    val remainingHtlcPenaltyTxs = rvk.htlcPenaltyTxs.filterNot(htlcPenalty => bobOutpoints.contains(htlcPenalty.input.outPoint))
    assert(remainingHtlcPenaltyTxs.size == 2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 3, rvk.commitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 0, rvk.mainPenaltyTx.get.tx)
    if (!channelFeatures.paysDirectlyToWallet) {
      alice ! WatchTxConfirmedTriggered(BlockHeight(110), 1, rvk.claimMainOutputTx.get.tx)
    }
    alice ! WatchTxConfirmedTriggered(BlockHeight(110), 2, remainingHtlcPenaltyTxs.head.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 2, remainingHtlcPenaltyTxs.last.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 0, bobHtlcTimeoutTx.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(115), 1, bobHtlcSuccessTx2)
    assert(alice.stateName == CLOSING)

    alice ! WatchTxConfirmedTriggered(BlockHeight(120), 0, claimHtlcTimeoutPenalty.tx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(121), 0, claimHtlcSuccessPenalty2.tx)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv WatchOutputSpentTriggered (one revoked tx, counterparty published htlc-success tx, option_static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    testOutputSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchOutputSpentTriggered (one revoked tx, counterparty published htlc-success tx, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testOutputSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchOutputSpentTriggered (one revoked tx, counterparty published htlc-success tx, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testOutputSpentRevokedTx(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
  }

  test("recv WatchOutputSpentTriggered (one revoked tx, counterparty published aggregated htlc tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // bob publishes one of his revoked txs
    val revokedCloseFixture = prepareRevokedClose(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
    val bobRevokedCommit = revokedCloseFixture.bobRevokedTxs(2)
    val commitmentFormat = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.commitmentFormat
    alice ! WatchFundingSpentTriggered(bobRevokedCommit.commitTxAndRemoteSig.commitTx.tx)
    awaitCond(alice.stateData.isInstanceOf[DATA_CLOSING])
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].commitments.params.commitmentFormat == ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    val rvk = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head
    assert(rvk.commitTx == bobRevokedCommit.commitTxAndRemoteSig.commitTx.tx)
    assert(rvk.htlcPenaltyTxs.size == 4)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)

    // alice publishes the penalty txs and watches outputs
    (1 to 6).foreach(_ => alice2blockchain.expectMsgType[PublishTx]) // 2 main outputs and 4 htlcs
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == rvk.commitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == rvk.claimMainOutputTx.get.tx.txid)
    (1 to 5).foreach(_ => alice2blockchain.expectMsgType[WatchOutputSpent]) // main output penalty and 4 htlc penalties
    alice2blockchain.expectNoMessage(1 second)

    // bob claims multiple htlc outputs in a single transaction (this is possible with anchor outputs because signatures
    // use sighash_single | sighash_anyonecanpay)
    val bobHtlcTxs = bobRevokedCommit.htlcTxsAndRemoteSigs.collect {
      case HtlcTxAndRemoteSig(txInfo: HtlcSuccessTx, _) =>
        val preimage = revokedCloseFixture.htlcsAlice.collectFirst { case (add, preimage) if add.id == txInfo.htlcId => preimage }.get
        assert(Crypto.sha256(preimage) == txInfo.paymentHash)
        Transactions.addSigs(txInfo, ByteVector64.Zeroes, ByteVector64.Zeroes, preimage, commitmentFormat)
      case HtlcTxAndRemoteSig(txInfo: HtlcTimeoutTx, _) =>
        Transactions.addSigs(txInfo, ByteVector64.Zeroes, ByteVector64.Zeroes, commitmentFormat)
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
    alice ! WatchOutputSpentTriggered(bobHtlcTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs.size == 4)
    val claimHtlcDelayedPenaltyTxs = alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.claimHtlcDelayedPenaltyTxs
    val spentOutpoints = Set(OutPoint(bobHtlcTx, 1), OutPoint(bobHtlcTx, 2), OutPoint(bobHtlcTx, 3), OutPoint(bobHtlcTx, 4))
    assert(claimHtlcDelayedPenaltyTxs.map(_.input.outPoint).toSet == spentOutpoints)
    claimHtlcDelayedPenaltyTxs.foreach(claimHtlcPenalty => Transaction.correctlySpends(claimHtlcPenalty.tx, bobHtlcTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobHtlcTx.txid)
    val publishedPenaltyTxs = Set(
      alice2blockchain.expectMsgType[PublishFinalTx],
      alice2blockchain.expectMsgType[PublishFinalTx],
      alice2blockchain.expectMsgType[PublishFinalTx],
      alice2blockchain.expectMsgType[PublishFinalTx]
    )
    assert(publishedPenaltyTxs.map(_.tx) == claimHtlcDelayedPenaltyTxs.map(_.tx).toSet)
    val watchedOutpoints = Seq(
      alice2blockchain.expectMsgType[WatchOutputSpent],
      alice2blockchain.expectMsgType[WatchOutputSpent],
      alice2blockchain.expectMsgType[WatchOutputSpent],
      alice2blockchain.expectMsgType[WatchOutputSpent]
    ).map(w => OutPoint(w.txId, w.outputIndex)).toSet
    assert(watchedOutpoints == spentOutpoints)
    alice2blockchain.expectNoMessage(1 second)
  }

  private def testRevokedTxConfirmed(f: FixtureParam, channelFeatures: ChannelFeatures): Unit = {
    import f._
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.channelFeatures == channelFeatures)
    val initOutputCount = channelFeatures.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => 4
      case DefaultCommitmentFormat => 2
    }
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.size == initOutputCount)

    // bob's second commit tx contains 2 incoming htlcs
    val (bobRevokedTx, htlcs1) = {
      val (_, htlc1) = addHtlc(35000000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc2) = addHtlc(20000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
      assert(bobCommitTx.txOut.size == initOutputCount + 2)
      (bobCommitTx, Seq(htlc1, htlc2))
    }

    // bob's third commit tx contains 1 of the previous htlcs and 2 new htlcs
    val htlcs2 = {
      val (_, htlc3) = addHtlc(25000000 msat, alice, bob, alice2bob, bob2alice)
      val (_, htlc4) = addHtlc(18000000 msat, alice, bob, alice2bob, bob2alice)
      failHtlc(htlcs1.head.id, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.size == initOutputCount + 3)
      Seq(htlc3, htlc4)
    }

    // alice's first htlc has been failed
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Fail]].htlc == htlcs1.head)
    alice2relayer.expectNoMessage(1 second)

    // bob publishes one of his revoked txs which quickly confirms
    alice ! WatchFundingSpentTriggered(bobRevokedTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(100), 1, bobRevokedTx)
    awaitCond(alice.stateName == CLOSING)

    // alice should fail all pending htlcs
    val htlcFails = Seq(
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]],
      alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]
    ).map(_.htlc).toSet
    assert(htlcFails == Set(htlcs1(1), htlcs2(0), htlcs2(1)))
    alice2relayer.expectNoMessage(1 second)
  }

  test("recv WatchTxConfirmedTriggered (one revoked tx, pending htlcs)") { f =>
    testRevokedTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey))
  }

  test("recv WatchTxConfirmedTriggered (one revoked tx, pending htlcs, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testRevokedTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs))
  }

  test("recv WatchTxConfirmedTriggered (one revoked tx, pending htlcs, anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testRevokedTxConfirmed(f, ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx))
  }

  test("recv ChannelReestablish") { f =>
    import f._
    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
    val bobCommitments = bob.stateData.asInstanceOf[DATA_CLOSING].commitments
    val bobCurrentPerCommitmentPoint = TestConstants.Bob.channelKeyManager.commitmentPoint(
      TestConstants.Bob.channelKeyManager.keyPath(bobCommitments.params.localParams, bobCommitments.params.channelConfig),
      bobCommitments.localCommitIndex)

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

}
