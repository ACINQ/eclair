/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.channel.states.b

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, SatoshiLong, TxId}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.SingleKeyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingConfirmed, WatchPublished}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{FullySignedSharedTransaction, PartiallySignedSharedTransaction}
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer.{LiquidityPurchaseSigned, OpenChannelResponse}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingSignedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alicePeer: TestProbe, bobPeer: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, wallet: SingleKeyOnChainWallet, aliceListener: TestProbe, bobListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new SingleKeyOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags(announceChannel = false)
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val bobContribution = if (channelType.features.contains(Features.ZeroConf)) None else Some(LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, Some(TestConstants.defaultLiquidityRates)))
    val requestFunding_opt = if (test.tags.contains(ChannelStateTestsTags.LiquidityAds)) Some(LiquidityAds.RequestFunding(TestConstants.nonInitiatorFundingSatoshis, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)) else None
    val (initiatorPushAmount, nonInitiatorPushAmount) = if (test.tags.contains("both_push_amount")) (Some(TestConstants.initiatorPushAmount), Some(TestConstants.nonInitiatorPushAmount)) else (None, None)
    val commitFeerate = channelType.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => TestConstants.feeratePerKw
      case _: Transactions.AnchorOutputsCommitmentFormat => TestConstants.anchorOutputsFeeratePerKw
    }
    val aliceListener = TestProbe()
    val bobListener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelAborted])
      bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, commitFeerate, TestConstants.feeratePerKw, fundingTxFeeBudget_opt = None, initiatorPushAmount, requireConfirmedInputs = false, requestFunding_opt, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType, replyTo = aliceOpenReplyTo.ref.toTyped)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, bobContribution, dualFunded = true, nonInitiatorPushAmount, requireConfirmedInputs = false, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId] // final channel id
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId] // final channel id
      // Alice and Bob complete the interactive-tx protocol.
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bobContribution match {
        case Some(_) => bob2alice.expectMsgType[TxAddInput]
        case None => bob2alice.expectMsgType[TxComplete]
      }
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bobContribution match {
        case Some(_) => bob2alice.expectMsgType[TxAddOutput]
        case None => bob2alice.expectMsgType[TxComplete]
      }
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxComplete]
      alice2bob.forward(bob)
      aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Created]
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alicePeer, bobPeer, alice2bob, bob2alice, alice2blockchain, bob2blockchain, wallet, aliceListener, bobListener)))
    }
  }

  test("complete interactive-tx protocol", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // Bob sends its signatures first as he contributed less than Alice.
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(bobData.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(bobData.latestFundingTx.sharedTx.isInstanceOf[PartiallySignedSharedTransaction])
    val fundingTxId = bobData.latestFundingTx.sharedTx.asInstanceOf[PartiallySignedSharedTransaction].txId
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)

    // Alice receives Bob's signatures and sends her own signatures.
    bob2alice.forward(alice)
    assert(listener.expectMsgType[TransactionPublished].tx.txid == fundingTxId)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(aliceData.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(aliceData.latestFundingTx.sharedTx.isInstanceOf[FullySignedSharedTransaction])
    assert(aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx.txid == fundingTxId)
  }

  test("complete interactive-tx protocol (zero-conf)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.ScidAlias), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // Bob sends its signatures first as he contributed less than Alice.
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(bobData.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(bobData.latestFundingTx.sharedTx.isInstanceOf[PartiallySignedSharedTransaction])
    val fundingTxId = bobData.latestFundingTx.sharedTx.asInstanceOf[PartiallySignedSharedTransaction].tx.buildUnsignedTx().txid
    assert(bob2blockchain.expectMsgType[WatchPublished].txId == fundingTxId)
    bob2blockchain.expectNoMessage(100 millis)

    // Alice receives Bob's signatures and sends her own signatures.
    bob2alice.forward(alice)
    assert(listener.expectMsgType[TransactionPublished].tx.txid == fundingTxId)
    assert(alice2blockchain.expectMsgType[WatchPublished].txId == fundingTxId)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(aliceData.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(aliceData.latestFundingTx.sharedTx.isInstanceOf[FullySignedSharedTransaction])
    assert(aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx.txid == fundingTxId)
  }

  test("complete interactive-tx protocol (with push amount)", Tag(ChannelStateTestsTags.DualFunding), Tag("both_push_amount")) { f =>
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    val expectedBalanceAlice = TestConstants.fundingSatoshis.toMilliSatoshi + TestConstants.nonInitiatorPushAmount - TestConstants.initiatorPushAmount
    assert(expectedBalanceAlice == 900_000_000.msat)
    val expectedBalanceBob = TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi + TestConstants.initiatorPushAmount - TestConstants.nonInitiatorPushAmount
    assert(expectedBalanceBob == 600_000_000.msat)

    // Bob sends its signatures first as he contributed less than Alice.
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(bobData.commitments.latest.localCommit.spec.toLocal == expectedBalanceBob)
    assert(bobData.commitments.latest.localCommit.spec.toRemote == expectedBalanceAlice)

    // Alice receives Bob's signatures and sends her own signatures.
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(aliceData.commitments.latest.localCommit.spec.toLocal == expectedBalanceAlice)
    assert(aliceData.commitments.latest.localCommit.spec.toRemote == expectedBalanceBob)
  }

  test("complete interactive-tx protocol (with liquidity ads)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.LiquidityAds)) { f =>
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // Bob sends its signatures first as he contributed less than Alice.
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val fundingTxId = bobData.latestFundingTx.sharedTx.asInstanceOf[PartiallySignedSharedTransaction].txId
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)

    // Bob signed a liquidity purchase.
    bobPeer.fishForMessage() {
      case l: LiquidityPurchaseSigned =>
        assert(l.purchase.paymentDetails == LiquidityAds.PaymentDetails.FromChannelBalance)
        assert(l.fundingTxIndex == 0)
        assert(l.txId == fundingTxId)
        true
      case _ => false
    }

    // Alice receives Bob's signatures and sends her own signatures.
    bob2alice.forward(alice)
    assert(listener.expectMsgType[TransactionPublished].tx.txid == fundingTxId)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx.txid == fundingTxId)
  }

  test("complete interactive-tx protocol (simple taproot channels, with push amount)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.DualFunding), Tag("both_push_amount")) { f =>
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    val expectedBalanceAlice = TestConstants.fundingSatoshis.toMilliSatoshi + TestConstants.nonInitiatorPushAmount - TestConstants.initiatorPushAmount
    assert(expectedBalanceAlice == 900_000_000.msat)
    val expectedBalanceBob = TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi + TestConstants.initiatorPushAmount - TestConstants.nonInitiatorPushAmount
    assert(expectedBalanceBob == 600_000_000.msat)

    // Bob sends its signatures first as he contributed less than Alice.
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(bobData.commitments.latest.localCommit.spec.toLocal == expectedBalanceBob)
    assert(bobData.commitments.latest.localCommit.spec.toRemote == expectedBalanceAlice)

    // Alice receives Bob's signatures and sends her own signatures.
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    assert(aliceData.commitments.latest.localCommit.spec.toLocal == expectedBalanceAlice)
    assert(aliceData.commitments.latest.localCommit.spec.toRemote == expectedBalanceBob)
  }

  test("recv invalid CommitSig", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val bobCommitSig = bob2alice.expectMsgType[CommitSig]
    val aliceCommitSig = alice2bob.expectMsgType[CommitSig]

    bob2alice.forward(alice, bobCommitSig.copy(signature = ByteVector64.Zeroes))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.length == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)

    alice2bob.forward(bob, aliceCommitSig.copy(signature = ByteVector64.Zeroes))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.length == 2)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv invalid TxSignatures", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    val bobSigs = bob2alice.expectMsgType[TxSignatures]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    bob2alice.forward(alice, bobSigs.copy(txId = randomTxId(), witnesses = Nil))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)

    // Bob has sent his signatures already, so he cannot close the channel yet.
    alice2bob.forward(bob, TxSignatures(channelId(alice), randomTxId(), Nil))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxInitRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[CommitSig]
    bob2alice.expectMsgType[CommitSig]

    alice2bob.forward(bob, TxInitRbf(channelId(alice), 0, FeeratePerKw(15_000 sat)))
    bob2alice.expectMsgType[Warning]
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)

    bob2alice.forward(alice, TxInitRbf(channelId(bob), 0, FeeratePerKw(15_000 sat)))
    alice2bob.expectMsgType[Warning]
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv TxAckRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[CommitSig]
    bob2alice.expectMsgType[CommitSig]

    alice2bob.forward(bob, TxAckRbf(channelId(alice)))
    bob2alice.expectMsgType[Warning]
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)

    bob2alice.forward(alice, TxAckRbf(channelId(bob)))
    alice2bob.expectMsgType[Warning]
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    alice ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)

    bob ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 2)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)

    alice ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(wallet.rolledback.size == 1)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)

    bob ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(wallet.rolledback.size == 2)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceData = alice.stateData
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData == aliceData)
    aliceListener.expectNoMessage(100 millis)

    val bobData = bob.stateData
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    assert(bob.stateData == bobData)
    bobListener.expectNoMessage(100 millis)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv INPUT_DISCONNECTED (commit_sig not received)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig] // Bob doesn't receive Alice's commit_sig
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    reconnect(f, fundingTxId)
  }

  test("recv INPUT_DISCONNECTED (commit_sig not received, next_commitment_number = 0)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig] // Bob doesn't receive Alice's commit_sig
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    reconnect(f, fundingTxId, aliceCommitmentNumber = 0, bobCommitmentNumber = 0)
  }

  test("recv INPUT_DISCONNECTED (commit_sig partially received)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    reconnect(f, fundingTxId)
  }

  test("recv INPUT_DISCONNECTED (commit_sig partially received, next_commitment_number = 0)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    reconnect(f, fundingTxId, aliceCommitmentNumber = 0)
  }

  test("recv INPUT_DISCONNECTED (commit_sig received)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    reconnect(f, fundingTxId)
  }

  test("recv INPUT_DISCONNECTED (tx_signatures received)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val listener = TestProbe()
    bob.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].signingSession.fundingTx.txId
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)

    assert(alice2bob.expectMsgType[ChannelReestablish].nextFundingTxId_opt.isEmpty)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[ChannelReestablish].nextFundingTxId_opt.contains(fundingTxId))
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)
    assert(listener.expectMsgType[TransactionPublished].tx.txid == fundingTxId)
  }

  private def reconnect(f: FixtureParam, fundingTxId: TxId, aliceCommitmentNumber: Long = 1, bobCommitmentNumber: Long = 1): Unit = {
    import f._

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(fundingTxId))
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 1)
    alice2bob.forward(bob, channelReestablishAlice.copy(nextLocalCommitmentNumber = aliceCommitmentNumber))
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    assert(channelReestablishBob.nextFundingTxId_opt.contains(fundingTxId))
    assert(channelReestablishBob.nextLocalCommitmentNumber == 1)
    bob2alice.forward(alice, channelReestablishBob.copy(nextLocalCommitmentNumber = bobCommitmentNumber))
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)

    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTxId)
    assert(listener.expectMsgType[TransactionPublished].tx.txid == fundingTxId)
  }

}
