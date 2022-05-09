/*
 * Copyright 2022 ACINQ SAS
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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.{SigHash, SigVersion}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingConfirmed, WatchFundingConfirmedTriggered, WatchFundingSpent}
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, NoOpOnChainWallet}
import fr.acinq.eclair.channel.InteractiveTx.{FullySignedSharedTransaction, FundingContributions, PartiallySignedSharedTransaction}
import fr.acinq.eclair.channel.InteractiveTxSpec.createChangeScript
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.ProcessCurrentBlockHeight
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions.{PlaceHolderPubKey, PlaceHolderSig}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, TestConstants, TestKitBaseClass, UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class WaitForDualFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, listener: TestProbe, wallet: NoOpOnChainWallet)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new NoOpOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])

    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val bobContribution = if (test.tags.contains("no-funding-contribution")) None else Some(TestConstants.nonInitiatorFundingSatoshis)
    val fundingAmount = bobContribution match {
      case Some(_) => TestConstants.fundingSatoshis + TestConstants.nonInitiatorFundingSatoshis
      case None => TestConstants.fundingSatoshis
    }
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, bobContribution, dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2blockchain.expectMsgType[SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[SetChannelId] // final channel id
      bob2blockchain.expectMsgType[SetChannelId] // final channel id

      // Alice always contributes one input and one output.
      val cid = channelId(bob)
      val privKey = randomKey()
      val fundingScript = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].fundingParams.fundingPubkeyScript
      val aliceInputAmount = TestConstants.fundingSatoshis + 75_000.sat
      val aliceFunding = InteractiveTxFunder.FundingSucceeded(FundingContributions(
        Seq(TxAddInput(cid, UInt64(0), Transaction(2, Seq(TxIn(OutPoint(randomBytes32(), 2), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig.bytes))), Seq(TxOut(aliceInputAmount, Script.pay2wpkh(privKey.publicKey))), 0), 0, 0)),
        Seq(TxAddOutput(cid, UInt64(0), fundingAmount, fundingScript), TxAddOutput(cid, UInt64(2), 60_000 sat, createChangeScript())),
      ))
      alice ! aliceFunding

      // Bob also contributes one input and one output.
      val bobInputAmount = TestConstants.nonInitiatorFundingSatoshis + 25_000.sat
      val bobFunding = InteractiveTxFunder.FundingSucceeded(FundingContributions(
        Seq(TxAddInput(cid, UInt64(1), Transaction(2, Seq(TxIn(OutPoint(randomBytes32(), 1), ByteVector.empty, 0, Script.witnessPay2wpkh(PlaceHolderPubKey, PlaceHolderSig.bytes))), Seq(TxOut(bobInputAmount, Script.pay2wpkh(privKey.publicKey))), 0), 0, 0)),
        Seq(TxAddOutput(cid, UInt64(1), 20_000 sat, createChangeScript())),
      ))
      bobContribution match {
        case Some(_) => bob ! bobFunding
        case None => // nothing to do
      }

      // Alice and Bob go through the interactive tx construction.
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

      // Alice and Bob sign the commitment and funding transaction.
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED && bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      val bobSharedTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].sharedTx
      bobContribution match {
        case Some(_) =>
          val bobSig = Transaction.signInput(bobSharedTx.buildUnsignedTx(), 1, Script.pay2pkh(privKey.publicKey), SigHash.SIGHASH_ALL, bobInputAmount, SigVersion.SIGVERSION_WITNESS_V0, privKey)
          bob ! PartiallySignedSharedTransaction(bobSharedTx, TxSignatures(cid, bobSharedTx.buildUnsignedTx().txid, Seq(Script.witnessPay2wpkh(privKey.publicKey, bobSig))))
        case None => // nothing to do
      }
      bob2alice.expectMsgType[TxSignatures]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

      val aliceSharedTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].sharedTx
      val aliceSig = Transaction.signInput(aliceSharedTx.buildUnsignedTx(), 0, Script.pay2pkh(privKey.publicKey), SigHash.SIGHASH_ALL, aliceInputAmount, SigVersion.SIGVERSION_WITNESS_V0, privKey)
      val aliceTxSigs = TxSignatures(cid, aliceSharedTx.buildUnsignedTx().txid, Seq(Script.witnessPay2wpkh(privKey.publicKey, aliceSig)))
      alice ! PartiallySignedSharedTransaction(aliceSharedTx, aliceTxSigs)
      // Alice publishes the funding tx.
      val fundingTx = listener.expectMsgType[TransactionPublished].tx
      assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId === fundingTx.txid)
      alice2bob.expectMsgType[TxSignatures]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
      // Bob publishes the funding tx.
      assert(listener.expectMsgType[TransactionPublished].tx.txid === fundingTx.txid)
      assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId === fundingTx.txid)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, listener, wallet)))
    }
  }

  test("recv WatchFundingConfirmedTriggered", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(listener.expectMsgType[TransactionConfirmed].tx === fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId === fundingTx.txid)
    alice2bob.expectMsgType[FundingLocked]
    awaitCond(alice.stateName === WAIT_FOR_DUAL_FUNDING_LOCKED)
  }

  test("recv CurrentBlockCount (funding in progress)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    // Alice republishes the highest feerate funding tx.
    assert(listener.expectMsgType[TransactionPublished].tx.txid === fundingTx.txid)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName === WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv CurrentBlockCount (funding in progress while offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    // Alice republishes the highest feerate funding tx.
    assert(listener.expectMsgType[TransactionPublished].tx.txid === fundingTx.txid)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName === OFFLINE)
  }

  test("recv CurrentBlockCount (funding double-spent)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) === Seq(fundingTx.txid))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding double-spent while offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) === Seq(fundingTx.txid))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached while offline)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv FundingLocked", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(listener.expectMsgType[TransactionConfirmed].tx === fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId === fundingTx.txid)
    val fundingLocked = bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(fundingLocked))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName === WAIT_FOR_DUAL_FUNDING_LOCKED)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    // We don't force-close yet because we don't know which funding tx will be confirmed.
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateName === WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "dual funding d34d")
    bob2blockchain.expectNoMessage(100 millis) // we don't publish our commit tx when we have nothing at stake
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_DUAL_FUNDING_CONFIRMED)))
  }

  test("recv CMD_FORCECLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_FORCECLOSE(sender.ref)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "force-close", WAIT_FOR_DUAL_FUNDING_CONFIRMED)))
  }

}
