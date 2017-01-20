package fr.acinq.eclair.channel.states.h

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ClosingStateSpec extends StateSpecBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, List[Transaction]]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "0B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "0A"))
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, blockchainA, alice2blockchain, bob2blockchain)

      val bobCommitTxes: List[Transaction] = (for (amt <- List(100000000, 200000000, 300000000)) yield {
        val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
        sign(alice, bob, alice2bob, bob2alice)
        val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
        fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
        sign(bob, alice, bob2alice, alice2bob)
        sign(alice, bob, alice2bob, bob2alice)
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

      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes))
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

  test("recv BITCOIN_CLOSE_DONE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

      // actual test starts here
      alice ! BITCOIN_CLOSE_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (our commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _) =>
    within(30 seconds) {
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes)
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.localCommitPublished.isDefined)

      // actual test starts here
      // we are notified afterwards from our watcher about the tx that we just published
      alice ! (BITCOIN_FUNDING_SPENT, aliceCommitTx)
      assert(alice.stateData == initialState) // this was a no-op
    }
  }

  test("recv BITCOIN_SPEND_OURS_DONE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, _) =>
    within(30 seconds) {
      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes())
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)

      // actual test starts here
      alice ! BITCOIN_SPEND_OURS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)
    }
  }

  test("recv BITCOIN_SPEND_THEIRS_DONE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].copy(remoteCommitPublished = None) == initialState)

      // actual test starts here
      alice ! BITCOIN_SPEND_THEIRS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)

      // alice publishes and watches the punishment tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectMsgType[PublishAsap]

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].copy(revokedCommitPublished = Nil) == initialState)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (multiple revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      // bob publishes multiple revoked txes (last one isn't revoked)
      for (bobRevokedTx <- bobCommitTxes.dropRight(1)) {
        alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)
        // alice publishes and watches the punishment tx
        alice2blockchain.expectMsgType[WatchConfirmed]
        alice2blockchain.expectMsgType[PublishAsap]
        alice2blockchain.expectMsgType[PublishAsap]
      }
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == bobCommitTxes.size - 1)
    }
  }

  test("recv BITCOIN_PUNISHMENT_DONE (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes) =>
    within(30 seconds) {
      mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)
      // alice publishes and watches the punishment tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectMsgType[PublishAsap]
      // TODO
      // awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedCommitPublished = Seq(RevokedCommitPublished(bobRevokedTx))))

      // actual test starts here
      alice ! BITCOIN_PUNISHMENT_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
