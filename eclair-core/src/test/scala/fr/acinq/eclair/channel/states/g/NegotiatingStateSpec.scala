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

package fr.acinq.eclair.channel.states.g

import akka.event.LoggingAdapter
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, SatoshiLong, Script}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishRawTx, PublishTx}
import fr.acinq.eclair.channel.states.{StateTestsBase, StateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.{ClosingSigned, Error, Shutdown}
import fr.acinq.eclair.{CltvExpiry, Features, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NegotiatingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      val sender = TestProbe()
      // alice initiates a closing
      if (test.tags.contains("fee2")) {
        alice.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(4319 sat)))
        bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(4319 sat)))
      }
      else {
        alice.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)))
        bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)))
      }
      bob ! CMD_CLOSE(sender.ref, None)
      val bobShutdown = bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      val aliceShutdown = alice2bob.expectMsgType[Shutdown]
      if (test.tags.contains(StateTestsTags.OptionUpfrontShutdownScript)) {
        assert(bobShutdown.scriptPubKey == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
        assert(aliceShutdown.scriptPubKey == alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localParams.defaultFinalScriptPubKey)
      }
      awaitCond(alice.stateName == NEGOTIATING)
      // NB: at this point, alice has already computed and sent the first ClosingSigned message
      // In order to force a fee negotiation, we will change the current fee before forwarding
      // the Shutdown message to alice, so that alice computes a different initial closing fee.
      if (test.tags.contains("fee2")) {
        alice.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(4316 sat)))
        bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(4316 sat)))
      } else {
        alice.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(5000 sat)))
        bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(5000 sat)))
      }
      alice2bob.forward(bob)
      awaitCond(bob.stateName == NEGOTIATING)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 5000000000L msat, ByteVector32(ByteVector.fill(32)(1)), cltvExpiry = CltvExpiry(300000), onion = TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMsg(200 millis)
  }

  def testClosingSigned(f: FixtureParam): Unit = {
    import f._
    // alice initiates the negotiation
    val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    // bob answers with a counter proposition
    val bobCloseSig1 = bob2alice.expectMsgType[ClosingSigned]
    assert(aliceCloseSig1.feeSatoshis > bobCloseSig1.feeSatoshis)
    // actual test starts here
    val initialState = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
    bob2alice.forward(alice)
    val aliceCloseSig2 = alice2bob.expectMsgType[ClosingSigned]
    // BOLT 2: If the receiver doesn't agree with the fee it SHOULD propose a value strictly between the received fee-satoshis and its previously-sent fee-satoshis
    assert(aliceCloseSig2.feeSatoshis < aliceCloseSig1.feeSatoshis && aliceCloseSig2.feeSatoshis > bobCloseSig1.feeSatoshis)
    awaitCond(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.map(_.localClosingSigned) == initialState.closingTxProposed.last.map(_.localClosingSigned) :+ aliceCloseSig2)
    val Some(closingTx) = alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    assert(closingTx.tx.txOut.length === 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
    if (alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.channelFeatures.hasFeature(Features.OptionUpfrontShutdownScript)) {
      // check that the closing tx uses Alice and Bob's default closing scripts
      val expectedLocalScript = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localParams.defaultFinalScriptPubKey
      val expectedRemoteScript = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localParams.defaultFinalScriptPubKey
      assert(closingTx.tx.txOut.map(_.publicKeyScript).toSet === Set(expectedLocalScript, expectedRemoteScript))
    }
    assert(aliceCloseSig2.feeSatoshis > Transactions.weight2fee(TestConstants.anchorOutputsFeeratePerKw, closingTx.tx.weight())) // NB: closing fee is allowed to be higher than commit tx fee when using anchor outputs
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") {
    testClosingSigned _
  }

  test("recv ClosingSigned (anchor outputs)", Tag(StateTestsTags.AnchorOutputs)) {
    testClosingSigned _
  }

  test("recv ClosingSigned (anchor outputs, upfront shutdown scripts)", Tag(StateTestsTags.AnchorOutputs), Tag(StateTestsTags.OptionUpfrontShutdownScript)) {
    testClosingSigned _
  }

  private def testFeeConverge(f: FixtureParam) = {
    import f._
    var aliceCloseFee, bobCloseFee = 0.sat
    do {
      aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
      alice2bob.forward(bob)
      if (!bob2blockchain.msgAvailable) {
        bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
        bob2alice.forward(alice)
      }
    } while (!alice2blockchain.msgAvailable && !bob2blockchain.msgAvailable)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee) (fee 1)") { f =>
    testFeeConverge(f)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee) (fee 2)", Tag("fee2")) { f =>
    testFeeConverge(f)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee) (fee 1, upfront shutdown script)", Tag("fee1"), Tag(StateTestsTags.OptionUpfrontShutdownScript)) { f =>
    testFeeConverge(f)
  }

  test("recv ClosingSigned (nothing at stake)", Tag(StateTestsTags.NoPushMsat)) { f =>
    import f._
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    assert(aliceCloseFee === bobCloseFee)
    bob2alice.forward(alice)
    val mutualCloseTxAlice = alice2blockchain.expectMsgType[PublishRawTx].tx
    val mutualCloseTxBob = bob2blockchain.expectMsgType[PublishRawTx].tx
    assert(mutualCloseTxAlice === mutualCloseTxBob)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTxAlice.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTxBob.txid)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) == List(mutualCloseTxAlice))
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.map(_.tx) == List(mutualCloseTxBob))
  }

  test("recv ClosingSigned (fee too high)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    sender.send(bob, aliceCloseSig.copy(feeSatoshis = 99000 sat)) // sig doesn't matter, it is checked later
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close fee: fee_satoshis=99000 sat"))
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx.txid === tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv ClosingSigned (invalid sig)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! aliceCloseSig.copy(signature = ByteVector64.Zeroes)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close signature"))
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx.txid === tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv WatchFundingSpentTriggered (counterparty's mutual close)") { f =>
    import f._
    var aliceCloseFee, bobCloseFee = 0.sat
    do {
      aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
      alice2bob.forward(bob)
      if (!bob2blockchain.msgAvailable) {
        bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
        if (aliceCloseFee != bobCloseFee) {
          bob2alice.forward(alice)
        }
      }
    } while (!alice2blockchain.msgAvailable && !bob2blockchain.msgAvailable)
    // at this point alice and bob have converged on closing fees, but alice has not yet received the final signature whereas bob has
    // bob publishes the mutual close and alice is notified that the funding tx has been spent
    // actual test starts here
    assert(alice.stateName == NEGOTIATING)
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishRawTx].tx
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    alice ! WatchFundingSpentTriggered(mutualCloseTx)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === mutualCloseTx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === mutualCloseTx.txid)
    alice2blockchain.expectNoMsg(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (an older mutual close)") { f =>
    import f._
    val aliceClose1 = alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    val bobClose1 = bob2alice.expectMsgType[ClosingSigned]
    bob2alice.forward(alice)
    val aliceClose2 = alice2bob.expectMsgType[ClosingSigned]
    assert(aliceClose2.feeSatoshis != bobClose1.feeSatoshis)
    // at this point alice and bob have not yet converged on closing fees, but bob decides to publish a mutual close with one of the previous sigs
    val d = bob.stateData.asInstanceOf[DATA_NEGOTIATING]
    implicit val log: LoggingAdapter = bob.underlyingActor.implicitLog
    val Right(bobClosingTx) = Closing.checkClosingSignature(Bob.channelKeyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, aliceClose1.feeSatoshis, aliceClose1.signature)

    alice ! WatchFundingSpentTriggered(bobClosingTx.tx)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === bobClosingTx.tx)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === bobClosingTx.tx.txid)
    alice2blockchain.expectNoMsg(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx.txid === tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === tx.txid)
  }

}
