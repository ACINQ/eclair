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

package fr.acinq.eclair.channel.states.h

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{OutPoint, ScriptFlags, Transaction, TxIn,MilliSatoshi}
import fr.acinq.eclair.{Globals, TestkitBaseClass}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import grizzled.slf4j.Logging
/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ClosingStateSpec extends TestkitBaseClass with StateTestsHelperMethods with Logging {

  type FixtureParam = Tuple9[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, TestProbe, List[PublishableTxs], TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val eventStream=TestProbe()
    system.eventStream.subscribe(eventStream.ref,classOf[CHANNEL_MUTUAL_CLOSE])
    system.eventStream.subscribe(eventStream.ref,classOf[CHANNEL_COMMIT_CLOSE])
    system.eventStream.subscribe(eventStream.ref,classOf[CHANNEL_CLOSE])
    system.eventStream.subscribe(eventStream.ref,classOf[CHANNEL_CLOSE_ROUNDING])
    
    val bobCommitTxes = within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      val bobCommitTxes: List[PublishableTxs] = (for (amt <- List(100000001, 200000000, 300000000)) yield { //non-exact satoshi amount to test rounding
        val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
        crossSign(alice, bob, alice2bob, bob2alice)
        relayer.expectMsgType[ForwardAdd]
        val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
        fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
        relayer.expectMsgType[ForwardFulfill]
        crossSign(bob, alice, bob2alice, alice2bob)
        relayer.expectMsgType[CommandBuffer.CommandAck]
        val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs
        bobCommitTx1 :: bobCommitTx2 :: Nil
      }).flatten

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      // NOTE
      // As opposed to other tests, we won't reach the target state (here CLOSING) at the end of the fixture.
      // The reason for this is that we may reach CLOSING state following several events:
      // - local commit
      // - remote commit
      // - revoked commit
      // and we want to be able to test the different scenarii.
      // Hence the NORMAL->CLOSING transition will occur in the individual tests.
      bobCommitTxes
    }
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer, bobCommitTxes, eventStream))
  }

  def mutualClose(alice: TestFSMRef[State, Data, Channel],
                  bob: TestFSMRef[State, Data, Channel],
                  alice2bob: TestProbe,
                  bob2alice: TestProbe,
                  alice2blockchain: TestProbe,
                  bob2blockchain: TestProbe): Unit = {
    val sender = TestProbe()
    // alice initiates a closing
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    // agreeing on a closing fee
    var aliceCloseFee, bobCloseFee = 0L
    do {
      aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
      alice2bob.forward(bob)
      bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
      bob2alice.forward(alice)
    } while (aliceCloseFee != bobCloseFee)
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
    // both nodes are now in CLOSING state with a mutual close tx pending for confirmation
  }

  test("recv CMD_ADD_HTLC") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

      // actual test starts here
      val sender = TestProbe()
      val add = CMD_ADD_HTLC(500000000, "11" * 32, expiry = 300000)
      sender.send(alice, add)
      val error = ChannelUnavailable(channelId(alice))
      sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), None)))
      alice2bob.expectNoMsg(200 millis)
    }
  }

  test("recv CMD_FULFILL_HTLC (unexisting htlc)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

      // actual test starts here
      val sender = TestProbe()
      sender.send(alice, CMD_FULFILL_HTLC(42, "42" * 32))
      sender.expectMsg(Failure(UnknownHtlcId(channelId(alice), 42)))

      // NB: nominal case is tested in IntegrationSpec
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (mutual close before converging)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      // alice initiates a closing
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      // agreeing on a closing fee
      val aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
      Globals.feeratesPerKw.set(FeeratesPerKw.single(100))
      alice2bob.forward(bob)
      val bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
      bob2alice.forward(alice)
      // they don't converge yet, but alice has a publishable commit tx now
      assert(aliceCloseFee != bobCloseFee)
      val Some(mutualCloseTx) = alice.stateData.asInstanceOf[DATA_NEGOTIATING].bestUnpublishedClosingTx_opt
      // let's make alice publish this closing tx
      alice ! Error("00" * 32, "")
      awaitCond(alice.stateName == CLOSING)
      assert(mutualCloseTx === alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last)

      // actual test starts here
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, mutualCloseTx)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (mutual close)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, eventStream) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
      // Test we publish closing flows for auditlogger
      logger.info(mutualCloseTx.txid.toString()+" "+mutualCloseTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_MUTUAL_CLOSE]==
          CHANNEL_MUTUAL_CLOSE(alice.stateData.asInstanceOf[HasCommitments].channelId,MilliSatoshi(199999999), MilliSatoshi(800000001), mutualCloseTx.txid, 6720L))
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (our commit)") { case (alice, _, _, _, alice2blockchain, _, _, _, _) =>
    within(30 seconds) {
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes)
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.localCommitPublished.isDefined)

      // actual test starts here
      // we are notified afterwards from our watcher about the tx that we just published
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, aliceCommitTx)
      assert(alice.stateData == initialState) // this was a no-op
    }
  }

  test("recv BITCOIN_OUTPUT_SPENT") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, relayer, _, _) =>
    within(30 seconds) {
      // alice sends an htlc to bob
      val (ra1, htlca1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      relayer.expectMsgType[ForwardAdd]
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes)
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx)) // commit tx
      alice2blockchain.expectMsgType[PublishAsap] // main-delayed-output
      alice2blockchain.expectMsgType[PublishAsap] // htlc-timeout
      alice2blockchain.expectMsgType[PublishAsap] // claim-delayed-output
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event.isInstanceOf[BITCOIN_TX_CONFIRMED]) // main-delayed-output
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event.isInstanceOf[BITCOIN_TX_CONFIRMED]) // claim-delayed-output
      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.localCommitPublished.isDefined)

      // actual test starts here
      relayer.expectMsgType[LocalChannelDown]

      // scenario 1: bob claims the htlc output from the commit tx using its preimage
      val claimHtlcSuccessFromCommitTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint("22" * 32, 0), signatureScript = "", sequence = 0, witness = Scripts.witnessClaimHtlcSuccessFromCommitTx("11" * 70, ra1, "33" * 130)) :: Nil, txOut = Nil, lockTime = 0)
      alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, claimHtlcSuccessFromCommitTx)
      assert(relayer.expectMsgType[ForwardFulfill].fulfill === UpdateFulfillHtlc(htlca1.channelId, htlca1.id, ra1))

      // scenario 2: bob claims the htlc output from his own commit tx using its preimage (let's assume both parties had published their commitment tx)
      val claimHtlcSuccessTx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint("22" * 32, 0), signatureScript = "", sequence = 0, witness = Scripts.witnessHtlcSuccess("11" * 70, "22" * 70, ra1, "33" * 130)) :: Nil, txOut = Nil, lockTime = 0)
      alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, claimHtlcSuccessTx)
      assert(relayer.expectMsgType[ForwardFulfill].fulfill === UpdateFulfillHtlc(htlca1.channelId, htlca1.id, ra1))

      assert(alice.stateData == initialState) // this was a no-op
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (local commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, _, _, eventStream) =>
    within(30 seconds) {
      // 800 000 000 to A at start
      // 200 000 000 to B at start
      // 600 000 001 paid in setup.
      
      // start with
      // 199 999 999 to A
      // 800 000 001 to B
      // alice sends an htlc to bob
      val (ra1, htlca1) = addHtlc(50000123, alice, bob, alice2bob, bob2alice) // odd amount to test rounding
      crossSign(alice, bob, alice2bob, bob2alice)
      val (ra12, htlca12) = addHtlc(100000, alice, bob, alice2bob, bob2alice) // below dust limit satoshi to test trimming in audit
      crossSign(alice, bob, alice2bob, bob2alice)
      
      // 149 899 876 to A
      // 800 000 001 to B
      //  50 000 123 htlc A->B
      //     100 000 htlc A->B
      
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes)
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx)) // commit tx
      val claimMainDelayedTx = alice2blockchain.expectMsgType[PublishAsap].tx // main-delayed-output
      val htlcTimeoutTx = alice2blockchain.expectMsgType[PublishAsap].tx // htlc-timeout
      val claimDelayedTx = alice2blockchain.expectMsgType[PublishAsap].tx // claim-delayed-output
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event.isInstanceOf[BITCOIN_TX_CONFIRMED]) // main-delayed-output
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event.isInstanceOf[BITCOIN_TX_CONFIRMED]) // claim-delayed-output
      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.localCommitPublished.isDefined)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(aliceCommitTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainDelayedTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(htlcTimeoutTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimDelayedTx), 0, 0)
      // no message for trimmed htlc of 100sat
      awaitCond(alice.stateName == CLOSED)

      // Test we publish closing flows for auditlogger
      logger.info("commit: "+aliceCommitTx.txid.toString()+" "+aliceCommitTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_COMMIT_CLOSE]==
          CHANNEL_COMMIT_CLOSE(alice.stateData.asInstanceOf[HasCommitments].channelId,MilliSatoshi(149899876), MilliSatoshi(800000001), aliceCommitTx.txid, 8960L))
      logger.info("mainDelayed: "+claimMainDelayedTx.txid.toString()+" "+claimMainDelayedTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_DELAYED_MAIN,alice.stateData.asInstanceOf[HasCommitments].channelId,136099L, claimMainDelayedTx.txid, 4840L)) // 876 msat gets rounded down to zero 1Sat HTLC added to fees..
      logger.info("timeout: "+htlcTimeoutTx.txid.toString()+" "+htlcTimeoutTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_HTLC_TIMEOUT_DELAYED,alice.stateData.asInstanceOf[HasCommitments].channelId,43370L, htlcTimeoutTx.txid, 6630L))
      logger.info("timeoutDelayed: "+claimDelayedTx.txid.toString()+" "+claimDelayedTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_HTLC_DELAYED,alice.stateData.asInstanceOf[HasCommitments].channelId,38530L, claimDelayedTx.txid, 4840L))
      
      //NOTE nothing published for the 100 Sat HTLC as it is below the dust limit so not in the commitment tx.    
          
      eventStream.expectMsgType[CHANNEL_CLOSE_ROUNDING]
      
      

    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last.commitTx.tx
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, eventStream) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last.commitTx.tx
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)
      val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
            // Test we publish closing flows for auditlogger

      logger.info("rc bobCommitTx: "+bobCommitTx.txid.toString()+" "+bobCommitTx.toString())
      logger.info("rc claimMain: "+claimMainTx.txid.toString()+" "+claimMainTx.toString())

      assert(eventStream.expectMsgType[CHANNEL_COMMIT_CLOSE]==
          CHANNEL_COMMIT_CLOSE(alice.stateData.asInstanceOf[HasCommitments].channelId,MilliSatoshi(199999999), MilliSatoshi(800000001), bobCommitTx.txid, 7240L))
      logger.info("rc mainDelayed: "+claimMainTx.txid.toString()+" "+claimMainTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_MAIN,alice.stateData.asInstanceOf[HasCommitments].channelId,188369L, claimMainTx.txid, 4390L))
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (future remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, eventStream) =>
    within(30 seconds) {
      val sender = TestProbe()
      val oldStateData = alice.stateData
      val (ra1, htlca1) = addHtlc(25000000, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(htlca1.id, ra1, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      // we simulate a disconnection
      sender.send(alice, INPUT_DISCONNECTED)
      sender.send(bob, INPUT_DISCONNECTED)
      awaitCond(alice.stateName == OFFLINE)
      awaitCond(bob.stateName == OFFLINE)
      // then we manually replace alice's state with an older one
      alice.setState(OFFLINE, oldStateData)
      // then we reconnect them
      sender.send(alice, INPUT_RECONNECTED(alice2bob.ref))
      sender.send(bob, INPUT_RECONNECTED(bob2alice.ref))
      // peers exchange channel_reestablish messages
      alice2bob.expectMsgType[ChannelReestablish]
      bob2alice.expectMsgType[ChannelReestablish]
      // alice then realizes it has an old state...
      bob2alice.forward(alice)
      // ... and ask bob to publish its current commitment
      val error = alice2bob.expectMsgType[Error]
      assert(new String(error.data) === PleasePublishYourCommitment(channelId(alice)).getMessage)
      // alice now waits for bob to publish its commitment
      awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)
      // bob is nice and publishes its commitment
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)
      // alice is able to claim its main output
      val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx
      Transaction.correctlySpends(claimMainTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].futureRemoteCommitPublished.isDefined)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobCommitTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)

      logger.info("frc bobCommitTx: "+bobCommitTx.txid.toString()+" "+bobCommitTx.toString())

      // the below are "wrong" but alice lost track! So must rely on Bob telling the truth!
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_ERROR_FUTURE_PUBLISHED,alice.stateData.asInstanceOf[HasCommitments].channelId, 0L, "",0L))
      assert(eventStream.expectMsgType[CHANNEL_COMMIT_CLOSE]==
          CHANNEL_COMMIT_CLOSE(alice.stateData.asInstanceOf[HasCommitments].channelId,MilliSatoshi(199999999), MilliSatoshi(800000001), bobCommitTx.txid, 32240L))
      logger.info("frc mainDelayed: "+claimMainTx.txid.toString()+" "+claimMainTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_MAIN,alice.stateData.asInstanceOf[HasCommitments].channelId,163369L, claimMainTx.txid, 4390L))
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)

      // alice publishes and watches the penalty tx
      alice2blockchain.expectMsgType[PublishAsap] // claim-main
      alice2blockchain.expectMsgType[PublishAsap] // main-penalty
      alice2blockchain.expectMsgType[PublishAsap] // htlc-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectMsgType[WatchSpent] // htlc-penalty
      alice2blockchain.expectNoMsg(1 second)

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].copy(revokedCommitPublished = Nil) == initialState)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (multiple revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes multiple revoked txes (last one isn't revoked)
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTxes(0).commitTx.tx)
      // alice publishes and watches the penalty tx
      alice2blockchain.expectMsgType[PublishAsap] // claim-main
      alice2blockchain.expectMsgType[PublishAsap] // main-penalty
      alice2blockchain.expectMsgType[PublishAsap] // htlc-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectMsgType[WatchSpent] // htlc-penalty
      alice2blockchain.expectNoMsg(1 second)

      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTxes(1).commitTx.tx)
      // alice publishes and watches the penalty tx
      alice2blockchain.expectMsgType[PublishAsap] // claim-main
      alice2blockchain.expectMsgType[PublishAsap] // main-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectNoMsg(1 second)

      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTxes(2).commitTx.tx)
      // alice publishes and watches the penalty tx
      alice2blockchain.expectMsgType[PublishAsap] // claim-main
      alice2blockchain.expectMsgType[PublishAsap] // main-penalty
      alice2blockchain.expectMsgType[PublishAsap] // htlc-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectMsgType[WatchSpent] // htlc-penalty
      alice2blockchain.expectNoMsg(1 second)

      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 3)
    }
  }

  test("recv BITCOIN_OUTPUT_SPENT (one revoked tx, counterparty published HtlcSuccess tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, eventStream) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx.commitTx.tx)
      // alice publishes and watches the penalty tx
      val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx // claim-main
      val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx // main-penalty
      val htlcPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx // htlc-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectMsgType[WatchSpent] // htlc-penalty
      alice2blockchain.expectNoMsg(1 second)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.commitTx == bobRevokedTx.commitTx.tx)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobRevokedTx.commitTx.tx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mainPenaltyTx), 0, 0)
      alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, htlcPenaltyTx) // we published this
      alice2blockchain.expectMsgType[WatchConfirmed] // htlc-penalty
      val bobHtlcSuccessTx = bobRevokedTx.htlcTxsAndSigs.head.txinfo.tx
      alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, bobHtlcSuccessTx) // bob published his HtlcSuccess tx
      alice2blockchain.expectMsgType[WatchConfirmed] // htlc-success
      val claimHtlcDelayedPenaltyTxs = alice2blockchain.expectMsgType[PublishAsap].tx // we publish a tx spending the output of bob's HtlcSuccess tx
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobHtlcSuccessTx), 0, 0) // bob won
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimHtlcDelayedPenaltyTxs), 0, 0) // bob won
      awaitCond(alice.stateName == CLOSED)

      // Test we publish closing flows for auditlogger
      logger.info("rv1 commit: "+bobRevokedTx.commitTx.tx.txid.toString()+" "+bobRevokedTx.commitTx.tx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_COMMIT,alice.stateData.asInstanceOf[HasCommitments].channelId,0L, bobRevokedTx.commitTx.tx.txid, 8961L))
      // above is zero amount as will not appear in wallet - although we know the key is a complex key in the shachain.

      logger.info("rv1  main: "+claimMainTx.txid.toString()+" "+claimMainTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_MAIN,alice.stateData.asInstanceOf[HasCommitments].channelId,686649L, claimMainTx.txid, 4390L))

      logger.info("rv1 mainPenaltyTx: "+mainPenaltyTx.txid.toString()+" "+mainPenaltyTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_MAIN_PENALTY,alice.stateData.asInstanceOf[HasCommitments].channelId,195150L, mainPenaltyTx.txid, 4850L))   

      // This is payment to bob that got on chain - but we then claim it. We log to fee as they reduced the amount we got.
      logger.info("rv1 htlcPenaltyTx: "+htlcPenaltyTx.txid.toString()+" "+htlcPenaltyTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_HTLC_REMOTE,alice.stateData.asInstanceOf[HasCommitments].channelId,0L, bobHtlcSuccessTx.txid, 7030L))   

      logger.info("rv1 claimHtlcDelayedPenaltyTxs: "+claimHtlcDelayedPenaltyTxs.txid.toString()+" "+claimHtlcDelayedPenaltyTxs.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_HTLC_DELAYED_PENALTY,alice.stateData.asInstanceOf[HasCommitments].channelId,88120L, claimHtlcDelayedPenaltyTxs.txid, 4850L))    
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes, eventStream) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx.commitTx.tx)
      // alice publishes and watches the penalty tx
      val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx // claim-main
      val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx // main-penalty
      val htlcPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx // htlc-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent] // main-penalty
      alice2blockchain.expectMsgType[WatchSpent] // htlc-penalty
      alice2blockchain.expectNoMsg(1 second)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.commitTx == bobRevokedTx.commitTx.tx)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobRevokedTx.commitTx.tx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mainPenaltyTx), 0, 0)
      alice ! WatchEventSpent(BITCOIN_OUTPUT_SPENT, htlcPenaltyTx)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(htlcPenaltyTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)

      // Test we publish closing flows for auditlogger
      logger.info("rv2 commit: "+bobRevokedTx.commitTx.tx.txid.toString()+" "+bobRevokedTx.commitTx.tx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_COMMIT,alice.stateData.asInstanceOf[HasCommitments].channelId,0L, bobRevokedTx.commitTx.tx.txid, 8961L))
      // above is zero amount as will not appear in wallet - although we know the key is a complex key in the shachain.

      logger.info("rv2  main: "+claimMainTx.txid.toString()+" "+claimMainTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_MAIN,alice.stateData.asInstanceOf[HasCommitments].channelId,686649L, claimMainTx.txid, 4390L))

      logger.info("rv2 mainPenaltyTx: "+mainPenaltyTx.txid.toString()+" "+mainPenaltyTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_MAIN_PENALTY,alice.stateData.asInstanceOf[HasCommitments].channelId,195150L, mainPenaltyTx.txid, 4850L))   

      logger.info("rv2 htlcPenaltyTx: "+htlcPenaltyTx.txid.toString()+" "+htlcPenaltyTx.toString())
      assert(eventStream.expectMsgType[CHANNEL_CLOSE]==
          CHANNEL_CLOSE(CLOSE_REVOKED_HTLC_PENALTY,alice.stateData.asInstanceOf[HasCommitments].channelId,94530L, htlcPenaltyTx.txid, 5470L))   

      //0+8960+686670+4370+195170+4830+94230+5770 = 1000000 So we have accounted for all the BTC that went into the channel!
    }
  }

  test("recv ChannelReestablish") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      val sender = TestProbe()
      sender.send(alice, ChannelReestablish(channelId(bob), 42, 42))
      val error = alice2bob.expectMsgType[Error]
      assert(new String(error.data) === FundingTxSpent(channelId(alice), initialState.spendingTxes.head).getMessage)
    }
  }

  test("recv CMD_CLOSE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg(Failure(ClosingAlreadyInProgress(channelId(alice))))
    }
  }

}
