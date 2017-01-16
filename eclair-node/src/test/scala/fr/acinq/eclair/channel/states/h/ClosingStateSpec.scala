package fr.acinq.eclair.channel.states.h

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, Data, State, _}
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
    // note that alice.initialFeeRate != bob.initialFeeRate
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(feeratePerKw = 20000), "A"))
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[OpenChannel]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[MakeFundingTx]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.forward(blockchainA)
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob ! BITCOIN_FUNDING_DEPTHOK
    bob2blockchain.expectMsgType[WatchLost]
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchLost]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // note : alice is funder and bob is fundee, so alice has all the money
    val bobCommitTxes: List[Transaction] = (for (amt <- List(100000000, 200000000, 300000000)) yield {
      val (r, htlc) = addHtlc(amt, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs._1.tx
      fulfillHtlc(htlc.id, r, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs._1.tx
      bobCommitTx1 :: bobCommitTx2 :: Nil
    }).flatten

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
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[Publish]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == CLOSING)
    awaitCond(bob.stateName == CLOSING)
    // both nodes are now in CLOSING state with a mutual close tx pending for confirmation
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, bobCommitTxes))
  }

  test("recv BITCOIN_CLOSE_DONE") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    within(30 seconds) {
      alice ! BITCOIN_CLOSE_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (our commit)") { case (_, _, _, _, _, _, _) =>
    within(30 seconds) {
      // this test needs a specific intialization because we need to have published our own commitment tx (that's why ignored fixture args)
      // to do that alice will receive an error packet when in NORMAL state, which will make her publish her commit tx and then reach CLOSING state

      val alice2bob = TestProbe()
      val bob2alice = TestProbe()
      val alice2blockchain = TestProbe()
      val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
      val bob2blockchain = TestProbe()
      val paymentHandler = TestProbe()
      // note that alice.initialFeeRate != bob.initialFeeRate
      val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
      val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(feeratePerKw = 20000), "A"))
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[OpenChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob ! BITCOIN_FUNDING_DEPTHOK
      bob2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchLost]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingLocked]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs._1.tx
      alice ! Error(0, "oops".getBytes)
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      assert(initialState.localCommitPublished == Some(aliceCommitTx))

      // actual test starts here
      alice ! (BITCOIN_FUNDING_SPENT, aliceCommitTx)
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
      val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
      val bob2blockchain = TestProbe()
      val paymentHandler = TestProbe()
      // note that alice.initialFeeRate != bob.initialFeeRate
      val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
      val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(feeratePerKw = 20000), "A"))
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob ! BITCOIN_FUNDING_DEPTHOK
      bob2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchLost]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingLocked]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs._1.tx
      alice ! Error(0, "oops".getBytes())
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished == Some(aliceCommitTx))

      // actual test starts here
      alice ! BITCOIN_SPEND_OURS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(remoteCommitPublished = Some(RemoteCommitPublished(bobCommitTx, Nil, Nil))))
    }
  }

  test("recv BITCOIN_SPEND_THEIRS_DONE") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes his last current commit tx, the one it had when entering NEGOTIATING state
      val bobCommitTx = bobCommitTxes.last
      assert(bobCommitTx.txOut.size == 2) // two main outputs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)
      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(remoteCommitPublished = Some(RemoteCommitPublished(bobCommitTx, Nil, Nil))))

      // actual test starts here
      alice ! BITCOIN_SPEND_THEIRS_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)

      // alice publishes and watches the stealing tx
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.expectMsgType[WatchConfirmed]

      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedCommitPublished = Seq(RevokedCommitPublished(bobRevokedTx))))
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (multiple revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      // bob publishes multiple revoked txes (last one isn't revoked)
      for (bobRevokedTx <- bobCommitTxes.dropRight(1)) {
        val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
        alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)
        // alice publishes and watches the stealing tx
        alice2blockchain.expectMsgType[Publish]
        alice2blockchain.expectMsgType[WatchConfirmed]
        awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedCommitPublished = initialState.revokedCommitPublished :+ RevokedCommitPublished(bobRevokedTx)))
      }
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == bobCommitTxes.size - 1)
    }
  }

  test("recv BITCOIN_STEAL_DONE (one revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, bobCommitTxes) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_CLOSING]
      // bob publishes one of his revoked txes
      val bobRevokedTx = bobCommitTxes.head
      alice ! (BITCOIN_FUNDING_SPENT, bobRevokedTx)
      // alice publishes and watches the stealing tx
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING] == initialState.copy(revokedCommitPublished = Seq(RevokedCommitPublished(bobRevokedTx))))

      // actual test starts here
      alice ! BITCOIN_STEAL_DONE
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
