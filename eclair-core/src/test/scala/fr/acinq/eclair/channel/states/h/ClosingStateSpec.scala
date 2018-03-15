package fr.acinq.eclair.channel.states.h

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{OutPoint, ScriptFlags, Transaction, TxIn}
import fr.acinq.eclair.{Globals, TestkitBaseClass}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment.{CommandBuffer, ForwardAdd, ForwardFulfill, Local}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ClosingStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple8[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, TestProbe, List[Transaction]]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      val bobCommitTxes: List[Transaction] = (for (amt <- List(100000000, 200000000, 300000000)) yield {
        val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
        crossSign(alice, bob, alice2bob, bob2alice)
        relayer.expectMsgType[ForwardAdd]
        val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
        fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
        relayer.expectMsgType[ForwardFulfill]
        crossSign(bob, alice, bob2alice, alice2bob)
        relayer.expectMsgType[CommandBuffer.CommandAck]
        val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
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

      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer, bobCommitTxes))
    }
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

  test("recv CMD_ADD_HTLC") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
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

  test("recv CMD_FULFILL_HTLC (unexisting htlc)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

      // actual test starts here
      val sender = TestProbe()
      sender.send(alice, CMD_FULFILL_HTLC(42, "42" * 32))
      sender.expectMsg(Failure(UnknownHtlcId(channelId(alice), 42)))

      // NB: nominal case is tested in IntegrationSpec
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (mutual close before converging)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
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

  test("recv BITCOIN_TX_CONFIRMED (mutual close)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val mutualCloseTx = alice.stateData.asInstanceOf[DATA_CLOSING].mutualClosePublished.last

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mutualCloseTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (our commit)") { case (alice, _, _, _, alice2blockchain, _, _, _) =>
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

  test("recv BITCOIN_HTLC_SPENT") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, relayer, _) =>
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

  test("recv BITCOIN_TX_CONFIRMED (local commit)") { case (alice, _, _, _, alice2blockchain, _, _, _) =>
    within(30 seconds) {
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes())
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      val claimMainDelayedTx = alice2blockchain.expectMsgType[PublishAsap].tx
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(aliceCommitTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainDelayedTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
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
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (future remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
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
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)

      // alice publishes and watches the penalty tx
      alice2blockchain.expectMsgType[PublishAsap] // claim-main
      alice2blockchain.expectMsgType[PublishAsap] // main-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent]

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].copy(revokedCommitPublished = Nil) == initialState)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (multiple revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes multiple revoked txes (last one isn't revoked)
      for (bobRevokedTx <- bobCommitTxes.dropRight(1)) {
        alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)
        // alice publishes and watches the penalty tx
        // alice publishes and watches the penalty tx
        alice2blockchain.expectMsgType[PublishAsap] // claim-main
        alice2blockchain.expectMsgType[PublishAsap] // main-penalty
        alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
        alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
        alice2blockchain.expectMsgType[WatchSpent]
      }
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == bobCommitTxes.size - 1)
    }
  }

  test("recv BITCOIN_TX_CONFIRMED (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobRevokedTx)
      // alice publishes and watches the penalty tx
      val claimMainTx = alice2blockchain.expectMsgType[PublishAsap].tx // claim-main
      val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx // main-penalty
      alice2blockchain.expectMsgType[WatchConfirmed] // revoked commit
      alice2blockchain.expectMsgType[WatchConfirmed] // claim-main
      alice2blockchain.expectMsgType[WatchSpent]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.head.commitTx == bobRevokedTx)

      // actual test starts here
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(bobRevokedTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(claimMainTx), 0, 0)
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(mainPenaltyTx), 0, 0)
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv ChannelReestablish") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      val sender = TestProbe()
      sender.send(alice, ChannelReestablish(channelId(bob), 42, 42))
      val error = alice2bob.expectMsgType[Error]
      assert(new String(error.data) === FundingTxSpent(channelId(alice), initialState.spendingTxes.head).getMessage)
    }
  }

  test("recv CMD_CLOSE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg(Failure(ClosingAlreadyInProgress(channelId(alice))))
    }
  }

}
