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

package fr.acinq.eclair.channel.states.b

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Script, Transaction}
import fr.acinq.bitcoin.{SigHash, SigVersion}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingConfirmed
import fr.acinq.eclair.channel.InteractiveTx.PartiallySignedSharedTransaction
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, CommitSig, Error, Init, OpenDualFundedChannel, TxSignatures}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingSignedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, aliceSigs: TxSignatures, bobSigs: TxSignatures, wallet: NoOpOnChainWallet)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new NoOpOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._
    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, Some(TestConstants.nonInitiatorFundingSatoshis), dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2blockchain.expectMsgType[SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[SetChannelId] // final channel id
      bob2blockchain.expectMsgType[SetChannelId] // final channel id

      val privKey = randomKey()
      val (aliceInputAmount, aliceChangeAmount) = (TestConstants.fundingSatoshis + 75_000.sat, 60_000.sat)
      val (bobInputAmount, bobChangeAmount) = (TestConstants.nonInitiatorFundingSatoshis + 25_000.sat, 20_000.sat)
      fundDualFundingChannel(alice, privKey, aliceInputAmount, aliceChangeAmount, bob, privKey, bobInputAmount, bobChangeAmount, alice2bob, bob2alice)

      val unsignedTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].fundingTx
      val aliceSigs = {
        val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(privKey.publicKey), SigHash.SIGHASH_ALL, aliceInputAmount, SigVersion.SIGVERSION_WITNESS_V0, privKey)
        TxSignatures(channelId(alice), unsignedTx.txid, Seq(Script.witnessPay2wpkh(privKey.publicKey, sig)))
      }
      val bobSigs = {
        val sig = Transaction.signInput(unsignedTx, 1, Script.pay2pkh(privKey.publicKey), SigHash.SIGHASH_ALL, bobInputAmount, SigVersion.SIGVERSION_WITNESS_V0, privKey)
        TxSignatures(channelId(bob), unsignedTx.txid, Seq(Script.witnessPay2wpkh(privKey.publicKey, sig)))
      }
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOrigin, alice2bob, bob2alice, alice2blockchain, bob2blockchain, aliceSigs, bobSigs, wallet)))
    }
  }

  test("recv CommitSig", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceCommit = alice2bob.expectMsgType[CommitSig]
    val bobCommit = bob2alice.expectMsgType[CommitSig]

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelSignatureReceived])

    alice2bob.forward(bob, aliceCommit)
    listener.expectMsgType[ChannelSignatureReceived]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.forward(alice, bobCommit)
    listener.expectMsgType[ChannelSignatureReceived]
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv CommitSig (invalid sig)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceCommit = alice2bob.expectMsgType[CommitSig]
    val bobCommit = bob2alice.expectMsgType[CommitSig]

    alice2bob.forward(bob, aliceCommit.copy(signature = bobCommit.signature))
    bob2alice.expectMsgType[Error]

    bob2alice.forward(alice, bobCommit.copy(signature = aliceCommit.signature))
    alice2bob.expectMsgType[Error]
  }

  test("recv TxSignatures", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val txListener = TestProbe()
    system.eventStream.subscribe(txListener.ref, classOf[TransactionPublished])

    val fundingTxId = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].fundingTx.txid
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // Bob signs the funding transaction with his wallet.
    bob ! PartiallySignedSharedTransaction(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].sharedTx, bobSigs)
    bob2alice.expectMsg(bobSigs)
    bob2alice.forward(alice)
    bob2alice.expectNoMessage(100 millis)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId === fundingTxId)
    bob2blockchain.expectNoMessage(100 millis)
    txListener.expectNoMessage(100 millis)
    awaitCond(bob.stateName === WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    // Alice signs the funding transaction with her wallet.
    alice ! PartiallySignedSharedTransaction(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].sharedTx, aliceSigs)
    alice2bob.expectMsg(aliceSigs)
    alice2bob.expectNoMessage(100 millis)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId === fundingTxId)
    alice2blockchain.expectNoMessage(100 millis)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelOpened]
    assert(txListener.expectMsgType[TransactionPublished].tx.txid === fundingTxId)
    awaitCond(bob.stateName === WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxSignatures (before CommitSig)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[CommitSig]
    bob2alice.expectMsgType[CommitSig]

    alice ! bobSigs
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! aliceSigs
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv TxSignatures (invalid sig)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    val invalidBobSigs = bobSigs.copy(witnesses = aliceSigs.witnesses)
    alice ! invalidBobSigs
    alice ! PartiallySignedSharedTransaction(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED].sharedTx, aliceSigs)
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv Status.Failure (wallet error)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    alice ! Status.Failure(new RuntimeException("could not sign inputs"))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! Status.Failure(new RuntimeException("could not sign inputs"))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! Error(channelId(alice), "oops")
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! Error(channelId(bob), "oops")
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)

    alice ! c
    sender.expectMsg(RES_SUCCESS(c, channelId(alice)))
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]

    bob ! c
    sender.expectMsg(RES_SUCCESS(c, channelId(bob)))
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
