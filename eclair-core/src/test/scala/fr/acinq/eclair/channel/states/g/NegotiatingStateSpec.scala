/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.Status.Failure
import akka.event.LoggingAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment.Local
import fr.acinq.eclair.wire.{ClosingSigned, Error, Shutdown}
import fr.acinq.eclair.{Globals, TestkitBaseClass}
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._
import scala.util.Success

/**
  * Created by PM on 05/07/2016.
  */

class NegotiatingStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      val sender = TestProbe()
      // alice initiates a closing
      if (test.tags.contains("fee2")) Globals.feeratesPerKw.set(FeeratesPerKw.single(4319)) else Globals.feeratesPerKw.set(FeeratesPerKw.single(10000))
      sender.send(bob, CMD_CLOSE(None))
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NEGOTIATING)
      // NB: at this point, alice has already computed and sent the first ClosingSigned message
      // In order to force a fee negotiation, we will change the current fee before forwarding
      // the Shutdown message to alice, so that alice computes a different initial closing fee.
      if (test.tags.contains("fee2")) Globals.feeratesPerKw.set(FeeratesPerKw.single(4316)) else Globals.feeratesPerKw.set(FeeratesPerKw.single(5000))
      alice2bob.forward(bob)
      awaitCond(bob.stateName == NEGOTIATING)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(500000000, "11" * 32, cltvExpiry = 300000)
    sender.send(alice, add)
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), None, Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") { f =>
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
  }

  private def testFeeConverge(f: FixtureParam) = {
    import f._
    var aliceCloseFee, bobCloseFee = 0L
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

  test("recv ClosingSigned (fee too high)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(bob, aliceCloseSig.copy(feeSatoshis = 99000)) // sig doesn't matter, it is checked later
  val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data).startsWith("invalid close fee: fee_satoshis=99000"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv ClosingSigned (invalid sig)") { f =>
    import f._
    val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
    val sender = TestProbe()
    val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(bob, aliceCloseSig.copy(signature = "00" * 64))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data).startsWith("invalid close signature"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv BITCOIN_FUNDING_SPENT (counterparty's mutual close)") { f =>
    import f._
    var aliceCloseFee, bobCloseFee = 0L
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
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
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
    val Success(bobClosingTx) = Closing.checkClosingSignature(Bob.keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(aliceClose1.feeSatoshis), aliceClose1.signature)

    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobClosingTx)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.expectNoMsg(100 millis)
    assert(alice.stateName == CLOSING)
  }


  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(ClosingAlreadyInProgress(channelId(alice))))
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error("00" * 32, "oops".getBytes())
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
  }

}
