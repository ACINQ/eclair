package fr.acinq.eclair.channel.simulator.states.h

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{Satoshi, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.simulator.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, Data, State, _}
import fr.acinq.eclair.{TestBitcoinClient, _}
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ClosingStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, List[Transaction]]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    // note that alice.initialFeeRate != bob.initialFeeRate
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(initialFeeRate = 20000), "A"))
    alice2bob.expectMsgType[open_channel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_channel]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[MakeAnchor]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[open_anchor]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_commit_sig]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.forward(blockchainA)
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob ! BITCOIN_ANCHOR_DEPTHOK
    bob2blockchain.expectMsgType[WatchLost]
    bob2alice.expectMsgType[open_complete]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchLost]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[open_complete]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // note : alice is funder and bob is fundee, so alice has all the money
    val bobCommitTxes: List[Transaction] = (for (amt <- List(100000000, 200000000, 300000000)) yield {
      val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      bobCommitTx1 :: bobCommitTx2 :: Nil
    }).flatten

    val sender = TestProbe()
    // alice initiates a closing
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[close_clearing]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[close_clearing]
    bob2alice.forward(alice)
    // agreeing on a closing fee
    var aliceCloseFee, bobCloseFee = 0L
    do {
      aliceCloseFee = alice2bob.expectMsgType[close_signature].closeFee
      alice2bob.forward(bob)
      bobCloseFee = bob2alice.expectMsgType[close_signature].closeFee
      bob2alice.forward(alice)
    } while (aliceCloseFee != bobCloseFee)
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[Publish]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
    // both nodes are now in CLOSING state with a mutual close tx pending for confirmation
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv BITCOIN_CLOSE_DONE") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    within(30 seconds) {
      alice ! BITCOIN_CLOSE_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (our commit)") { case (_, _, _, _, _, _, _) =>
    within(30 seconds) {
      // this test needs a specific intialization because we need to have published our own commitment tx (that's why ignored fixture args)
      // to do that alice will receive an error packet when in NORMAL state, which will make her publish her commit tx and then reach CLOSING state

      val alice2bob = TestProbe()
      val bob2alice = TestProbe()
      val alice2blockchain = TestProbe()
      val blockchainA = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
      val bob2blockchain = TestProbe()
      val paymentHandler = TestProbe()
      // note that alice.initialFeeRate != bob.initialFeeRate
      val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
      val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(initialFeeRate = 20000), "A"))
      alice2bob.expectMsgType[open_channel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_anchor]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_commit_sig]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob ! BITCOIN_ANCHOR_DEPTHOK
      bob2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[open_complete]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchLost]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_complete]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.ourCommitPublished == Some(aliceCommitTx))

      // actual test starts here
      alice ! (BITCOIN_ANCHOR_SPENT, aliceCommitTx)
      assert(alice.stateData == initialState)
    }
  }

  test("recv BITCOIN_SPEND_OURS_DONE") { case (_, _, _, _, _, _, _) =>
    within(30 seconds) {
      // this test needs a specific intialization because we need to have published our own commitment tx (that's why ignored fixture args)
      // to do that alice will receive an error packet when in NORMAL state, which will make her publish her commit tx and then reach CLOSING state

      val alice2bob = TestProbe()
      val bob2alice = TestProbe()
      val alice2blockchain = TestProbe()
      val blockchainA = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
      val bob2blockchain = TestProbe()
      val paymentHandler = TestProbe()
      // note that alice.initialFeeRate != bob.initialFeeRate
      val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
      val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(initialFeeRate = 20000), "A"))
      alice2bob.expectMsgType[open_channel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_anchor]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_commit_sig]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob ! BITCOIN_ANCHOR_DEPTHOK
      bob2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[open_complete]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchLost]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_complete]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].ourCommitPublished == Some(aliceCommitTx))

      // actual test starts here
      alice ! BITCOIN_SPEND_OURS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_ANCHOR_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(theirCommitPublished = Some(bobCommitTx)))
    }
  }

  test("recv BITCOIN_SPEND_THEIRS_DONE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_ANCHOR_SPENT, bobCommitTx)
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(theirCommitPublished = Some(bobCommitTx)))

      // actual test starts here
      alice ! BITCOIN_SPEND_THEIRS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_ANCHOR_SPENT, bobRevokedTx)

      // alice publishes and watches the stealing tx
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.expectMsgType[WatchConfirmed]

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedPublished = Seq(bobRevokedTx)))
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (multiple revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      // bob publishes multiple revoked txes (last one isn't revoked)
      for (bobRevokedTx <- bobCommitTxes.dropRight(1)) {
        val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
        alice ! (BITCOIN_ANCHOR_SPENT, bobRevokedTx)
        // alice publishes and watches the stealing tx
        alice2blockchain.expectMsgType[Publish]
        alice2blockchain.expectMsgType[WatchConfirmed]
        awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedPublished = initialState.revokedPublished :+ bobRevokedTx))
      }
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedPublished.size == bobCommitTxes.size - 1)
    }
  }

  test("recv BITCOIN_STEAL_DONE (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_ANCHOR_SPENT, bobRevokedTx)
      // alice publishes and watches the stealing tx
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedPublished = Seq(bobRevokedTx)))

      // actual test starts here
      alice ! BITCOIN_STEAL_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
