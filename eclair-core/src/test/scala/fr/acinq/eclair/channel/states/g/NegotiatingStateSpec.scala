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
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{ClosingSigned, Error, Shutdown}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshiLong, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NegotiatingStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

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
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[Shutdown]
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
    // BOLT 2: If the receiver [doesn't agree with the fee] it SHOULD propose a value strictly between the received fee-satoshis and its previously-sent fee-satoshis
    assert(aliceCloseSig2.feeSatoshis < aliceCloseSig1.feeSatoshis && aliceCloseSig2.feeSatoshis > bobCloseSig1.feeSatoshis)
    awaitCond(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.map(_.localClosingSigned) == initialState.closingTxProposed.last.map(_.localClosingSigned) :+ aliceCloseSig2)
    val Some(closingTx) = alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
    assert(closingTx.txOut.length === 2) // NB: in the anchor outputs case, anchors are removed from the closing tx
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") {
    testClosingSigned _
  }

  test("recv ClosingSigned (anchor outputs)", Tag("anchor_outputs")) {
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

  test("recv ClosingSigned (nothing at stake)", Tag("no_push_msat")) { f =>
    import f._
    val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
    alice2bob.forward(bob)
    val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
    assert(aliceCloseFee === bobCloseFee)
    bob2alice.forward(alice)
    val mutualCloseTxAlice = alice2blockchain.expectMsgType[PublishAsap].tx
    val mutualCloseTxBob = bob2blockchain.expectMsgType[PublishAsap].tx
    assert(mutualCloseTxAlice === mutualCloseTxBob)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(mutualCloseTxAlice))
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(mutualCloseTxBob))
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished == List(mutualCloseTxAlice))
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished == List(mutualCloseTxBob))
  }

  test("recv ClosingSigned (fee too high)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(bob, aliceCloseSig.copy(feeSatoshis = 99000 sat)) // sig doesn't matter, it is checked later
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close fee: fee_satoshis=Satoshi(99000)"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv ClosingSigned (invalid sig)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! aliceCloseSig.copy(signature = ByteVector64.Zeroes)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid close signature"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv BITCOIN_FUNDING_SPENT (counterparty's mutual close)") { f =>
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
    val mutualCloseTx = bob2blockchain.expectMsgType[PublishAsap].tx
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(mutualCloseTx))
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, mutualCloseTx)
    alice2blockchain.expectMsg(PublishAsap(mutualCloseTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === mutualCloseTx.txid)
    alice2blockchain.expectNoMsg(100 millis)
    assert(alice.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (an older mutual close)") { f =>
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

    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobClosingTx)
    alice2blockchain.expectMsg(PublishAsap(bobClosingTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].txId === bobClosingTx.txid)
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
    val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

}
